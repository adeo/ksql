/*
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.ksql.planner.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.metastore.KsqlStream;
import io.confluent.ksql.metastore.KsqlTable;
import io.confluent.ksql.metastore.KsqlTopic;
import io.confluent.ksql.metastore.StructuredDataSource;
import io.confluent.ksql.physical.AddTimestampColumn;
import io.confluent.ksql.serde.KsqlTopicSerDe;
import io.confluent.ksql.streams.MaterializedFactory;
import io.confluent.ksql.structured.SchemaKStream;
import io.confluent.ksql.structured.SchemaKTable;
import io.confluent.ksql.util.KafkaTopicClient;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.SchemaUtil;
import io.confluent.ksql.util.timestamp.TimestampExtractionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.concurrent.Immutable;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.ValueMapperWithKey;
import org.apache.kafka.streams.kstream.Window;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.internals.SessionWindow;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.apache.kafka.streams.state.KeyValueStore;

@Immutable
public class StructuredDataSourceNode
    extends PlanNode {

  private static final ValueMapperWithKey<String, GenericRow, GenericRow>
      nonWindowedValueMapper = (key, row) -> {
        if (row != null) {
          row.getColumns().add(0, key);
        }
        return row;
      };

  private static final ValueMapperWithKey<Windowed<String>, GenericRow, GenericRow>
      windowedMapper = (key, row) -> {
        if (row != null) {
          final Window window = key.window();
          final String end = window instanceof SessionWindow ? String.valueOf(window.end()) : "-";
          final String rowKey = String.format("%s : Window{start=%d end=%s}",
              key.key(), window.start(), end);
          row.getColumns().add(0, rowKey);
        }
        return row;
      };

  private final StructuredDataSource structuredDataSource;
  private final Schema schema;
  final Function<KsqlConfig, MaterializedFactory> materializedFactorySupplier;

  // TODO: pass in the "assignments" and the "outputs" separately
  // TODO: (i.e., get rid if the symbol := symbol idiom)
  @JsonCreator
  public StructuredDataSourceNode(
      @JsonProperty("id") final PlanNodeId id,
      @JsonProperty("structuredDataSource") final StructuredDataSource structuredDataSource,
      @JsonProperty("schema") final Schema schema
  ) {
    this(id, structuredDataSource, schema, MaterializedFactory::create);
  }

  public StructuredDataSourceNode(
      final PlanNodeId id,
      final StructuredDataSource structuredDataSource,
      final Schema schema,
      final Function<KsqlConfig, MaterializedFactory> materializedFactorySupplier) {
    super(id, structuredDataSource.getDataSourceType());
    Objects.requireNonNull(structuredDataSource, "structuredDataSource can't be null");
    Objects.requireNonNull(schema, "schema can't be null");
    Objects.requireNonNull(materializedFactorySupplier, "materializedFactorySupplier");
    this.schema = schema;
    this.structuredDataSource = structuredDataSource;
    this.materializedFactorySupplier = materializedFactorySupplier;
  }

  public String getTopicName() {
    return structuredDataSource.getTopicName();
  }

  @Override
  public Schema getSchema() {
    return schema;
  }

  @Override
  public Field getKeyField() {
    return structuredDataSource.getKeyField();
  }

  public StructuredDataSource getStructuredDataSource() {
    return structuredDataSource;
  }

  @Override
  public int getPartitions(final KafkaTopicClient kafkaTopicClient) {
    final String topicName = getStructuredDataSource().getKsqlTopic().getKafkaTopicName();
    final Map<String, TopicDescription> descriptions
        = kafkaTopicClient.describeTopics(Arrays.asList(topicName));
    if (!descriptions.containsKey(topicName)) {
      throw new KsqlException("Could not get topic description for " + topicName);
    }
    return descriptions.get(topicName).partitions().size();
  }

  @Override
  public List<PlanNode> getSources() {
    return null;
  }

  @Override
  public <C, R> R accept(final PlanVisitor<C, R> visitor, final C context) {
    return visitor.visitStructuredDataSourceNode(this, context);
  }

  @SuppressWarnings("unchecked")
  @Override
  public SchemaKStream<?> buildStream(
      final StreamsBuilder builder,
      final KsqlConfig ksqlConfig,
      final KafkaTopicClient kafkaTopicClient,
      final FunctionRegistry functionRegistry,
      final Map<String, Object> props,
      final Supplier<SchemaRegistryClient> schemaRegistryClientFactory
  ) {
    final int timeStampColumnIndex = getTimeStampColumnIndex();
    final TimestampExtractor timestampExtractor = getTimestampExtractionPolicy()
        .create(timeStampColumnIndex);

    final KsqlTopicSerDe ksqlTopicSerDe = getStructuredDataSource()
        .getKsqlTopic().getKsqlTopicSerDe();
    final Serde<GenericRow> genericRowSerde =
        ksqlTopicSerDe.getGenericRowSerde(
            SchemaUtil.removeImplicitRowTimeRowKeyFromSchema(getSchema()),
            ksqlConfig, false, schemaRegistryClientFactory);

    if (getDataSourceType() == StructuredDataSource.DataSourceType.KTABLE) {
      final KsqlTable table = (KsqlTable) getStructuredDataSource();

      final KTable<?, GenericRow> kTable = createKTable(
          builder,
          getAutoOffsetReset(props),
          genericRowSerde,
          table.getKsqlTopic().getKsqlTopicSerDe().getGenericRowSerde(
              getSchema(), ksqlConfig, true, schemaRegistryClientFactory),
          timestampExtractor,
          ksqlConfig
      );
      return new SchemaKTable<>(
          getSchema(),
          kTable,
          getKeyField(),
          new ArrayList<>(),
          table.getKeySerde(),
          SchemaKStream.Type.SOURCE,
          ksqlConfig,
          functionRegistry,
          schemaRegistryClientFactory.get()
      );
    }

    final KsqlStream stream = (KsqlStream) getStructuredDataSource();
    final KStream kstream = createKStream(builder, timestampExtractor, genericRowSerde);

    return new SchemaKStream<>(
        getSchema(),
        kstream,
        getKeyField(), new ArrayList<>(),
        stream.getKeySerde(),
        SchemaKStream.Type.SOURCE, ksqlConfig, functionRegistry,
        schemaRegistryClientFactory.get()
    );
  }

  private Topology.AutoOffsetReset getAutoOffsetReset(final Map<String, Object> props) {
    if (props.containsKey(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)) {
      final String offestReset = props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG).toString();
      if (offestReset.equalsIgnoreCase("EARLIEST")) {
        return Topology.AutoOffsetReset.EARLIEST;
      } else if (offestReset.equalsIgnoreCase("LATEST")) {
        return Topology.AutoOffsetReset.LATEST;
      }
    }
    return null;
  }

  private int getTimeStampColumnIndex() {
    final String timestampFieldName = getTimestampExtractionPolicy().timestampField();
    if (timestampFieldName == null) {
      return -1;
    }
    if (timestampFieldName.contains(".")) {
      final Integer index = findMatchingTimestampField(timestampFieldName);
      if (index != null) {
        return index;
      }
    } else {
      for (int i = 2; i < schema.fields().size(); i++) {
        final Field field = schema.fields().get(i);
        if (field.name().contains(".")) {
          if (timestampFieldName.equals(field.name().substring(field.name().indexOf(".") + 1))) {
            return i - 2;
          }
        } else {
          if (timestampFieldName.equals(field.name())) {
            return i - 2;
          }
        }
      }
    }
    return -1;
  }

  private Integer findMatchingTimestampField(final String timestampFieldName) {
    for (int i = 2; i < schema.fields().size(); i++) {
      final Field field = schema.fields().get(i);
      if (field.name().contains(".")) {
        if (timestampFieldName.equals(field.name())) {
          return i - 2;
        }
      } else {
        if (timestampFieldName
            .substring(timestampFieldName.indexOf(".") + 1)
            .equals(field.name())) {
          return i - 2;
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private KStream<?, GenericRow> createKStream(
      final StreamsBuilder builder,
      final TimestampExtractor timestampExtractor,
      final Serde<GenericRow> genericRowSerde) {
    final KsqlStream ksqlStream = (KsqlStream) getStructuredDataSource();

    if (ksqlStream.hasWindowedKey()) {
      return stream(builder, timestampExtractor, genericRowSerde,
          (Serde<Windowed<String>>)ksqlStream.getKeySerde(), windowedMapper);
    }

    return stream(builder, timestampExtractor, genericRowSerde,
        (Serde<String>)ksqlStream.getKeySerde(), nonWindowedValueMapper);
  }

  private <K> KStream<K, GenericRow> stream(
      final StreamsBuilder builder,
      final TimestampExtractor timestampExtractor,
      final Serde<GenericRow> genericRowSerde,
      final Serde<K> keySerde,
      final ValueMapperWithKey<K, GenericRow, GenericRow> mapper) {

    final Consumed<K, GenericRow> consumed = Consumed
        .with(keySerde, genericRowSerde)
        .withTimestampExtractor(timestampExtractor);

    return builder
        .stream(getStructuredDataSource().getKsqlTopic().getKafkaTopicName(), consumed)
        .mapValues(mapper)
        .transformValues(new AddTimestampColumn());
  }

  private String getReduceOpName() {
    return getId().toString() + "-REDUCE";
  }

  @SuppressWarnings("unchecked")
  private KTable<?, GenericRow> createKTable(
      final StreamsBuilder builder, 
      final Topology.AutoOffsetReset autoOffsetReset,
      final Serde<GenericRow> genericRowSerde,
      final Serde<GenericRow> genericRowSerdeAfterRead,
      final TimestampExtractor timestampExtractor,
      final KsqlConfig ksqlConfig) {
    // to build a table we apply the following transformations:
    // 1. Create a KStream on the changelog topic.
    // 2. mapValues to add the ROWKEY column
    // 3. transformValues to add the ROWTIME column. transformValues is required to access the
    //    streams ProcessorContext which has the timestamp for the record. Also, transformValues
    //    is only available for KStream (not KTable). This is why we have to create a KStream
    //    first instead of a KTable.
    // 4. mapValues to transform null records into Optional<GenericRow>.EMPTY. We eventually need
    //    to aggregate the KStream to produce the KTable. However the KStream aggregator filters
    //    out records with null keys or values. For tables, a null value for a key represents
    //    that the key was deleted. So we preserve these "tombstone" records by converting them
    //    to a not-null representation.
    // 5. Aggregate the KStream into a KTable using a custom aggregator that handles Optional.EMPTY
    final KsqlTable ksqlTable = (KsqlTable) getStructuredDataSource();

    if (ksqlTable.isWindowed()) {
      return table(
          builder, autoOffsetReset, timestampExtractor, ksqlTable.getKsqlTopic(), windowedMapper,
          (Serde<Windowed<String>>)ksqlTable.getKeySerde(),
          genericRowSerde, genericRowSerdeAfterRead, ksqlConfig);
    }

    return table(
        builder, autoOffsetReset, timestampExtractor, ksqlTable.getKsqlTopic(),
        nonWindowedValueMapper, (Serde<String>)ksqlTable.getKeySerde(),
        genericRowSerde, genericRowSerdeAfterRead, ksqlConfig);
  }

  private <K> KTable<?, GenericRow> table(
      final StreamsBuilder builder,
      final Topology.AutoOffsetReset autoOffsetReset,
      final TimestampExtractor timestampExtractor,
      final KsqlTopic ksqlTopic,
      final ValueMapperWithKey<K, GenericRow, GenericRow> valueMapper,
      final Serde<K> keySerde,
      final Serde<GenericRow> genericRowSerde,
      final Serde<GenericRow> genericRowSerdeAfterRead,
      final KsqlConfig ksqlConfig
  ) {
    final Consumed<K, GenericRow> consumed = Consumed
        .with(keySerde, genericRowSerde)
        .withOffsetResetPolicy(autoOffsetReset)
        .withTimestampExtractor(timestampExtractor);

    final Materialized<K, GenericRow, KeyValueStore<Bytes, byte[]>> materialized =
        materializedFactorySupplier.apply(ksqlConfig).create(
            keySerde,
            genericRowSerdeAfterRead,
            getReduceOpName());
    return builder
        .stream(ksqlTopic.getKafkaTopicName(), consumed)
        .mapValues(valueMapper)
        .transformValues(new AddTimestampColumn())
        .mapValues(Optional::ofNullable)
        .groupByKey()
        .aggregate(
            () -> null,
            (k, value, oldValue) -> value.orElse(null),
            materialized);
  }

  public StructuredDataSource.DataSourceType getDataSourceType() {
    return structuredDataSource.getDataSourceType();
  }

  public TimestampExtractionPolicy getTimestampExtractionPolicy() {
    return structuredDataSource.getTimestampExtractionPolicy();
  }
}
