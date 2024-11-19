package com.tencent.supersonic.headless.core.translator.calcite.planner;

import com.tencent.supersonic.common.calcite.Configuration;
import com.tencent.supersonic.common.pojo.enums.EngineType;
import com.tencent.supersonic.headless.api.pojo.enums.AggOption;
import com.tencent.supersonic.headless.core.pojo.Database;
import com.tencent.supersonic.headless.core.pojo.MetricQueryParam;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import com.tencent.supersonic.headless.core.translator.calcite.s2sql.Constants;
import com.tencent.supersonic.headless.core.translator.calcite.s2sql.DataModel;
import com.tencent.supersonic.headless.core.translator.calcite.schema.S2SemanticSchema;
import com.tencent.supersonic.headless.core.translator.calcite.schema.SchemaBuilder;
import com.tencent.supersonic.headless.core.translator.calcite.sql.Renderer;
import com.tencent.supersonic.headless.core.translator.calcite.sql.node.DataSourceNode;
import com.tencent.supersonic.headless.core.translator.calcite.sql.node.SemanticNode;
import com.tencent.supersonic.headless.core.translator.calcite.sql.render.FilterRender;
import com.tencent.supersonic.headless.core.translator.calcite.sql.render.OutputRender;
import com.tencent.supersonic.headless.core.translator.calcite.sql.render.SourceRender;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlValidatorScope;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;

/** parsing from query dimensions and metrics */
@Slf4j
public class AggPlanner implements Planner {

    private MetricQueryParam metricReq;
    private final S2SemanticSchema schema;
    private SqlValidatorScope scope;
    private SqlNode parserNode;
    private String sourceId;
    private boolean isAgg = false;
    private AggOption aggOption = AggOption.DEFAULT;

    public AggPlanner(S2SemanticSchema schema) {
        this.schema = schema;
    }

    private void parse() throws Exception {
        // find the match Datasource
        scope = SchemaBuilder.getScope(schema);
        List<DataModel> datasource = getMatchDataSource(scope);
        if (datasource == null || datasource.isEmpty()) {
            throw new Exception("datasource not found");
        }
        isAgg = getAgg(datasource.get(0));
        sourceId = String.valueOf(datasource.get(0).getSourceId());

        // build level by level
        LinkedList<Renderer> builders = new LinkedList<>();
        builders.add(new SourceRender());
        builders.add(new FilterRender());
        builders.add(new OutputRender());
        ListIterator<Renderer> it = builders.listIterator();
        int i = 0;
        Renderer previous = null;
        while (it.hasNext()) {
            Renderer renderer = it.next();
            if (previous != null) {
                previous.render(metricReq, datasource, scope, schema, !isAgg);
                renderer.setTable(previous
                        .builderAs(DataSourceNode.getNames(datasource) + "_" + String.valueOf(i)));
                i++;
            }
            previous = renderer;
        }
        builders.getLast().render(metricReq, datasource, scope, schema, !isAgg);
        parserNode = builders.getLast().builder();
    }

    private List<DataModel> getMatchDataSource(SqlValidatorScope scope) throws Exception {
        return DataSourceNode.getMatchDataSources(scope, schema, metricReq);
    }

    private boolean getAgg(DataModel dataModel) {
        if (!AggOption.DEFAULT.equals(aggOption)) {
            return AggOption.isAgg(aggOption);
        }
        // default by dataModel time aggregation
        if (Objects.nonNull(dataModel.getAggTime()) && !dataModel.getAggTime()
                .equalsIgnoreCase(Constants.DIMENSION_TYPE_TIME_GRANULARITY_NONE)) {
            if (!metricReq.isNativeQuery()) {
                return true;
            }
        }
        return isAgg;
    }

    @Override
    public void plan(QueryStatement queryStatement, AggOption aggOption) throws Exception {
        this.metricReq = queryStatement.getMetricQueryParam();
        if (metricReq.getMetrics() == null) {
            metricReq.setMetrics(new ArrayList<>());
        }
        if (metricReq.getDimensions() == null) {
            metricReq.setDimensions(new ArrayList<>());
        }
        if (metricReq.getLimit() == null) {
            metricReq.setLimit(0L);
        }
        this.aggOption = aggOption;
        // build a parse Node
        parse();
        // optimizer
        Database database = queryStatement.getOntology().getDatabase();
        EngineType engineType = EngineType.fromString(database.getType());
        optimize(engineType);
    }

    @Override
    public String getSql(EngineType engineType) {
        return SemanticNode.getSql(parserNode, engineType);
    }

    @Override
    public String getSourceId() {
        return sourceId;
    }

    @Override
    public String simplify(String sql, EngineType engineType) {
        try {
            SqlNode sqlNode =
                    SqlParser.create(sql, Configuration.getParserConfig(engineType)).parseStmt();
            if (Objects.nonNull(sqlNode)) {
                return SemanticNode.getSql(
                        SemanticNode.optimize(scope, schema, sqlNode, engineType), engineType);
            }
        } catch (Exception e) {
            log.error("optimize error {}", e.toString());
        }
        return "";
    }

    private void optimize(EngineType engineType) {
        if (Objects.isNull(schema.getRuntimeOptions())
                || Objects.isNull(schema.getRuntimeOptions().getEnableOptimize())
                || !schema.getRuntimeOptions().getEnableOptimize()) {
            return;
        }

        SqlNode optimizeNode = null;
        try {
            SqlNode sqlNode = SqlParser.create(SemanticNode.getSql(parserNode, engineType),
                    Configuration.getParserConfig(engineType)).parseStmt();
            if (Objects.nonNull(sqlNode)) {
                optimizeNode = SemanticNode.optimize(scope, schema, sqlNode, engineType);
            }
        } catch (Exception e) {
            log.error("optimize error {}", e);
        }

        if (Objects.nonNull(optimizeNode)) {
            parserNode = optimizeNode;
        }
    }

}
