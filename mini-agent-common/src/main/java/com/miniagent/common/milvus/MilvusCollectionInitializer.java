package com.miniagent.common.milvus;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Milvus collection 初始化工具。消除 3 处 ensureCollection() 重复代码。
 */
public final class MilvusCollectionInitializer {

    private static final Logger log = LoggerFactory.getLogger(MilvusCollectionInitializer.class);

    private MilvusCollectionInitializer() {}

    /**
     * 确保 collection 存在，不存在则创建并加载。
     *
     * @param client     Milvus 客户端
     * @param collection collection 名称
     * @param dimension  向量维度
     * @param fields     额外字段定义（不含 pk 和 vector，这两个自动添加）
     */
    public static void ensureCollection(MilvusClientV2 client, String collection,
                                        int dimension, List<FieldDef> fields) {
        Boolean has = client.hasCollection(HasCollectionReq.builder().collectionName(collection).build());
        if (Boolean.TRUE.equals(has)) return;

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        // 主键
        schema.addField(AddFieldReq.builder()
                .fieldName("pk").dataType(DataType.VarChar)
                .maxLength(128).isPrimaryKey(true).autoID(false).build());
        // 用户自定义字段
        for (FieldDef f : fields) {
            var builder = AddFieldReq.builder()
                    .fieldName(f.name()).dataType(f.type());
            if (f.maxLength() > 0) builder.maxLength(f.maxLength());
            schema.addField(builder.build());
        }
        // 向量字段
        schema.addField(AddFieldReq.builder()
                .fieldName("vector").dataType(DataType.FloatVector)
                .dimension(dimension).build());

        IndexParam index = IndexParam.builder()
                .fieldName("vector")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collection)
                .collectionSchema(schema)
                .indexParams(List.of(index))
                .build());

        client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
        log.info("已创建并加载 Milvus collection {}", collection);
    }

    /**
     * 字段定义。
     */
    public record FieldDef(String name, DataType type, int maxLength) {
        public static FieldDef varchar(String name, int maxLength) {
            return new FieldDef(name, DataType.VarChar, maxLength);
        }
        public static FieldDef int64(String name) {
            return new FieldDef(name, DataType.Int64, 0);
        }
    }
}
