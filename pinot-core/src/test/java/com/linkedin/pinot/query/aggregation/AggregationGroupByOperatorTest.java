/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.query.aggregation;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.linkedin.pinot.common.request.AggregationInfo;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.request.FilterOperator;
import com.linkedin.pinot.common.request.GroupBy;
import com.linkedin.pinot.common.response.BrokerResponse;
import com.linkedin.pinot.common.response.ServerInstance;
import com.linkedin.pinot.common.segment.ReadMode;
import com.linkedin.pinot.common.utils.DataTable;
import com.linkedin.pinot.common.utils.NamedThreadFactory;
import com.linkedin.pinot.common.utils.request.FilterQueryTree;
import com.linkedin.pinot.common.utils.request.RequestUtils;
import com.linkedin.pinot.core.block.query.IntermediateResultsBlock;
import com.linkedin.pinot.core.common.DataSource;
import com.linkedin.pinot.core.indexsegment.IndexSegment;
import com.linkedin.pinot.core.indexsegment.columnar.ColumnarSegmentLoader;
import com.linkedin.pinot.core.indexsegment.generator.SegmentGeneratorConfig;
import com.linkedin.pinot.core.operator.BReusableFilteredDocIdSetOperator;
import com.linkedin.pinot.core.operator.MProjectionOperator;
import com.linkedin.pinot.core.operator.UReplicatedProjectionOperator;
import com.linkedin.pinot.core.operator.query.AggregationFunctionGroupByOperator;
import com.linkedin.pinot.core.operator.query.MAggregationGroupByOperator;
import com.linkedin.pinot.core.operator.query.MDefaultAggregationFunctionGroupByOperator;
import com.linkedin.pinot.core.plan.Plan;
import com.linkedin.pinot.core.plan.PlanNode;
import com.linkedin.pinot.core.plan.maker.InstancePlanMakerImplV0;
import com.linkedin.pinot.core.plan.maker.PlanMaker;
import com.linkedin.pinot.core.query.aggregation.CombineService;
import com.linkedin.pinot.core.query.aggregation.groupby.AggregationGroupByOperatorService;
import com.linkedin.pinot.core.query.reduce.DefaultReduceService;
import com.linkedin.pinot.core.segment.creator.SegmentIndexCreationDriver;
import com.linkedin.pinot.core.segment.creator.impl.SegmentCreationDriverFactory;
import com.linkedin.pinot.core.segment.index.ColumnMetadata;
import com.linkedin.pinot.core.segment.index.IndexSegmentImpl;
import com.linkedin.pinot.core.segment.index.SegmentMetadataImpl;
import com.linkedin.pinot.core.util.DoubleComparisonUtil;
import com.linkedin.pinot.segments.v1.creator.SegmentTestUtils;
import com.linkedin.pinot.util.TestUtils;


public class AggregationGroupByOperatorTest {
  protected static Logger LOGGER = LoggerFactory.getLogger(AggregationGroupByOperatorTest.class);
  private final String AVRO_DATA = "data/test_sample_data.avro";
  private static File INDEX_DIR = new File(FileUtils.getTempDirectory() + File.separator
      + "TestAggregationGroupByOperator");
  private static File INDEXES_DIR = new File(FileUtils.getTempDirectory() + File.separator
      + "TestAggregationGroupByOperatorList");

  public static IndexSegment _indexSegment;
  private static List<IndexSegment> _indexSegmentList;

  public static AggregationInfo _paramsInfo;
  public static List<AggregationInfo> _aggregationInfos;
  public static int _numAggregations = 6;

  public Map<String, ColumnMetadata> _medataMap;
  public static GroupBy _groupBy;

  @BeforeClass
  public void setup() throws Exception {
    setupSegment();
    setupQuery();
    _indexSegmentList = new ArrayList<IndexSegment>();
  }

  @AfterClass
  public void tearDown() {
    if (INDEX_DIR.exists()) {
      FileUtils.deleteQuietly(INDEX_DIR);
    }
    if (INDEXES_DIR.exists()) {
      FileUtils.deleteQuietly(INDEXES_DIR);
    }
  }

  private void setupSegment() throws Exception {
    final String filePath = TestUtils.getFileFromResourceUrl(getClass().getClassLoader().getResource(AVRO_DATA));

    if (INDEX_DIR.exists()) {
      FileUtils.deleteQuietly(INDEX_DIR);
    }

    final SegmentGeneratorConfig config =
        SegmentTestUtils.getSegmentGenSpecWithSchemAndProjectedColumns(new File(filePath), INDEX_DIR, "time_day",
            TimeUnit.DAYS, "test");

    final SegmentIndexCreationDriver driver = SegmentCreationDriverFactory.get(null);
    driver.init(config);
    driver.build();

    LOGGER.info("built at : {}", INDEX_DIR.getAbsolutePath());
    final File indexSegmentDir = new File(INDEX_DIR, driver.getSegmentName());
    _indexSegment = ColumnarSegmentLoader.load(indexSegmentDir, ReadMode.heap);
    _medataMap = ((SegmentMetadataImpl) ((IndexSegmentImpl) _indexSegment).getSegmentMetadata()).getColumnMetadataMap();
  }

  public void setupQuery() {
    _aggregationInfos = getAggregationsInfo();
    final List<String> groupbyColumns = new ArrayList<String>();
    groupbyColumns.add("column11");
    _groupBy = new GroupBy();
    _groupBy.setColumns(groupbyColumns);
    _groupBy.setTopN(10);
  }

  private void setupSegmentList(int numberOfSegments) throws Exception {
    final String filePath = TestUtils.getFileFromResourceUrl(getClass().getClassLoader().getResource(AVRO_DATA));
    _indexSegmentList.clear();
    if (INDEXES_DIR.exists()) {
      FileUtils.deleteQuietly(INDEXES_DIR);
    }
    INDEXES_DIR.mkdir();

    for (int i = 0; i < numberOfSegments; ++i) {
      final File segmentDir = new File(INDEXES_DIR, "segment_" + i);

      final SegmentGeneratorConfig config =
          SegmentTestUtils.getSegmentGenSpecWithSchemAndProjectedColumns(new File(filePath), segmentDir, "time_day",
              TimeUnit.DAYS, "test");

      final SegmentIndexCreationDriver driver = SegmentCreationDriverFactory.get(null);
      driver.init(config);
      driver.build();

      LOGGER.info("built at : {}", segmentDir.getAbsolutePath());
      _indexSegmentList.add(ColumnarSegmentLoader.load(new File(segmentDir, driver.getSegmentName()), ReadMode.heap));
    }
  }

  @Test
  public void testAggregationGroupBys() {
    final List<AggregationFunctionGroupByOperator> aggregationFunctionGroupByOperatorList =
        new ArrayList<AggregationFunctionGroupByOperator>();
    final BReusableFilteredDocIdSetOperator docIdSetOperator =
        new BReusableFilteredDocIdSetOperator(null, _indexSegment.getTotalDocs(), 5000);
    final Map<String, DataSource> dataSourceMap = getDataSourceMap();

    final MProjectionOperator projectionOperator = new MProjectionOperator(dataSourceMap, docIdSetOperator);

    for (int i = 0; i < _numAggregations; ++i) {
      final MDefaultAggregationFunctionGroupByOperator aggregationFunctionGroupByOperator =
          new MDefaultAggregationFunctionGroupByOperator(_aggregationInfos.get(i), _groupBy,
              new UReplicatedProjectionOperator(projectionOperator), true);
      aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);
    }

    final MAggregationGroupByOperator aggregationGroupByOperator =
        new MAggregationGroupByOperator(_indexSegment, _aggregationInfos, _groupBy, projectionOperator,
            aggregationFunctionGroupByOperatorList);

    final IntermediateResultsBlock block = (IntermediateResultsBlock) aggregationGroupByOperator.nextBlock();
    for (int i = 0; i < _numAggregations; ++i) {
      LOGGER.info("Result: {}", block.getAggregationGroupByOperatorResult().get(i));
    }
  }

  @Test
  public void testAggregationGroupBysWithCombine() {
    final List<AggregationFunctionGroupByOperator> aggregationFunctionGroupByOperatorList =
        new ArrayList<AggregationFunctionGroupByOperator>();
    final BReusableFilteredDocIdSetOperator docIdSetOperator =
        new BReusableFilteredDocIdSetOperator(null, _indexSegment.getTotalDocs(), 5000);
    final Map<String, DataSource> dataSourceMap = getDataSourceMap();

    final MProjectionOperator projectionOperator = new MProjectionOperator(dataSourceMap, docIdSetOperator);

    for (int i = 0; i < _numAggregations; ++i) {
      final MDefaultAggregationFunctionGroupByOperator aggregationFunctionGroupByOperator =
          new MDefaultAggregationFunctionGroupByOperator(_aggregationInfos.get(i), _groupBy,
              new UReplicatedProjectionOperator(projectionOperator), true);
      aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);
    }

    final MAggregationGroupByOperator aggregationGroupByOperator =
        new MAggregationGroupByOperator(_indexSegment, _aggregationInfos, _groupBy, projectionOperator,
            aggregationFunctionGroupByOperatorList);

    final IntermediateResultsBlock block = (IntermediateResultsBlock) aggregationGroupByOperator.nextBlock();

    for (int i = 0; i < _numAggregations; ++i) {
      LOGGER.info("Result 1: {}", block.getAggregationGroupByOperatorResult().get(i));
    }
    /////////////////////////////////////////////////////////////////////////
    final List<AggregationFunctionGroupByOperator> aggregationFunctionGroupByOperatorList1 =
        new ArrayList<AggregationFunctionGroupByOperator>();
    final BReusableFilteredDocIdSetOperator docIdSetOperator1 =
        new BReusableFilteredDocIdSetOperator(null, _indexSegment.getTotalDocs(), 5000);
    final Map<String, DataSource> dataSourceMap1 = getDataSourceMap();
    final MProjectionOperator projectionOperator1 = new MProjectionOperator(dataSourceMap1, docIdSetOperator1);

    for (int i = 0; i < _numAggregations; ++i) {
      final MDefaultAggregationFunctionGroupByOperator aggregationFunctionGroupByOperator1 =
          new MDefaultAggregationFunctionGroupByOperator(_aggregationInfos.get(i), _groupBy,
              new UReplicatedProjectionOperator(projectionOperator1), true);
      aggregationFunctionGroupByOperatorList1.add(aggregationFunctionGroupByOperator1);
    }

    final MAggregationGroupByOperator aggregationGroupByOperator1 =
        new MAggregationGroupByOperator(_indexSegment, _aggregationInfos, _groupBy, projectionOperator1,
            aggregationFunctionGroupByOperatorList1);

    final IntermediateResultsBlock block1 = (IntermediateResultsBlock) aggregationGroupByOperator1.nextBlock();

    for (int i = 0; i < _numAggregations; ++i) {
      LOGGER.info("Result 2: {}", block1.getAggregationGroupByOperatorResult().get(i));
    }
    CombineService.mergeTwoBlocks(getAggregationGroupByNoFilterBrokerRequest(), block, block1);

    LOGGER.info("Combined Result : ");
    for (int i = 0; i < _numAggregations; ++i) {
      LOGGER.info("Combined : {}", block.getAggregationGroupByOperatorResult().get(i));
    }
  }

  @Test
  public void testAggregationGroupBysWithDataTableEncodeAndDecode() throws Exception {
    final List<AggregationFunctionGroupByOperator> aggregationFunctionGroupByOperatorList =
        new ArrayList<AggregationFunctionGroupByOperator>();
    final BReusableFilteredDocIdSetOperator docIdSetOperator =
        new BReusableFilteredDocIdSetOperator(null, _indexSegment.getTotalDocs(), 5000);
    final Map<String, DataSource> dataSourceMap = getDataSourceMap();
    final MProjectionOperator projectionOperator = new MProjectionOperator(dataSourceMap, docIdSetOperator);

    for (int i = 0; i < _numAggregations; ++i) {
      final MDefaultAggregationFunctionGroupByOperator aggregationFunctionGroupByOperator =
          new MDefaultAggregationFunctionGroupByOperator(_aggregationInfos.get(i), _groupBy,
              new UReplicatedProjectionOperator(projectionOperator), true);
      aggregationFunctionGroupByOperatorList.add(aggregationFunctionGroupByOperator);
    }
    final MAggregationGroupByOperator aggregationGroupByOperator =
        new MAggregationGroupByOperator(_indexSegment, _aggregationInfos, _groupBy, projectionOperator,
            aggregationFunctionGroupByOperatorList);

    final IntermediateResultsBlock block = (IntermediateResultsBlock) aggregationGroupByOperator.nextBlock();

    for (int i = 0; i < _numAggregations; ++i) {
      LOGGER.info("Result 1: {}", block.getAggregationGroupByOperatorResult().get(i));
    }
    /////////////////////////////////////////////////////////////////////////
    final List<AggregationFunctionGroupByOperator> aggregationFunctionGroupByOperatorList1 =
        new ArrayList<AggregationFunctionGroupByOperator>();
    final BReusableFilteredDocIdSetOperator docIdSetOperator1 =
        new BReusableFilteredDocIdSetOperator(null, _indexSegment.getTotalDocs(), 5000);
    final Map<String, DataSource> dataSourceMap1 = getDataSourceMap();
    final MProjectionOperator projectionOperator1 = new MProjectionOperator(dataSourceMap1, docIdSetOperator1);

    for (int i = 0; i < _numAggregations; ++i) {
      final MDefaultAggregationFunctionGroupByOperator aggregationFunctionGroupByOperator1 =
          new MDefaultAggregationFunctionGroupByOperator(_aggregationInfos.get(i), _groupBy,
              new UReplicatedProjectionOperator(projectionOperator1), true);
      aggregationFunctionGroupByOperatorList1.add(aggregationFunctionGroupByOperator1);
    }
    final MAggregationGroupByOperator aggregationGroupByOperator1 =
        new MAggregationGroupByOperator(_indexSegment, _aggregationInfos, _groupBy, projectionOperator1,
            aggregationFunctionGroupByOperatorList1);

    final IntermediateResultsBlock block1 = (IntermediateResultsBlock) aggregationGroupByOperator1.nextBlock();

    for (int i = 0; i < _numAggregations; ++i) {
      LOGGER.info("Result 2: {}", block1.getAggregationGroupByOperatorResult().get(i));
    }
    CombineService.mergeTwoBlocks(getAggregationGroupByNoFilterBrokerRequest(), block, block1);

    for (int i = 0; i < _numAggregations; ++i) {
      LOGGER.info("Combined Result: {}", block.getAggregationGroupByOperatorResult().get(i));
    }

    final DataTable dataTable = block.getAggregationGroupByResultDataTable();

    final List<Map<String, Serializable>> results =
        AggregationGroupByOperatorService.transformDataTableToGroupByResult(dataTable);
    for (int i = 0; i < _numAggregations; ++i) {
      LOGGER.info("Decode AggregationResult from DataTable: {}", results.get(i));
    }
  }

  @Test
  public void testInnerSegmentPlanMakerForAggregationGroupByOperatorNoFilter() throws Exception {
    final BrokerRequest brokerRequest = getAggregationGroupByNoFilterBrokerRequest();
    final PlanMaker instancePlanMaker = new InstancePlanMakerImplV0();
    final PlanNode rootPlanNode = instancePlanMaker.makeInnerSegmentPlan(_indexSegment, brokerRequest);
    rootPlanNode.showTree("");
    // UAggregationGroupByOperator operator = (UAggregationGroupByOperator) rootPlanNode.run();
    final MAggregationGroupByOperator operator = (MAggregationGroupByOperator) rootPlanNode.run();
    final IntermediateResultsBlock resultBlock = (IntermediateResultsBlock) operator.nextBlock();
    LOGGER.info("RunningTime : {}", resultBlock.getTimeUsedMs());
    LOGGER.info("NumDocsScanned : {}", resultBlock.getNumDocsScanned());
    LOGGER.info("TotalDocs : {}", resultBlock.getTotalDocs());

    final AggregationGroupByOperatorService aggregationGroupByOperatorService =
        new AggregationGroupByOperatorService(_aggregationInfos, brokerRequest.getGroupBy());

    final Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    instanceResponseMap.put(new ServerInstance("localhost:0000"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:1111"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:2222"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:3333"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:4444"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:5555"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:6666"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:7777"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:8888"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:9999"), resultBlock.getAggregationGroupByResultDataTable());
    final List<Map<String, Serializable>> reducedResults =
        aggregationGroupByOperatorService.reduceGroupByOperators(instanceResponseMap);

    final List<JSONObject> jsonResult = aggregationGroupByOperatorService.renderGroupByOperators(reducedResults);
    LOGGER.info("Result {}", jsonResult);
  }

  @Test
  public void testInnerSegmentPlanMakerForAggregationGroupByOperatorWithFilter() throws Exception {
    final BrokerRequest brokerRequest = getAggregationGroupByWithFilterBrokerRequest();
    final PlanMaker instancePlanMaker = new InstancePlanMakerImplV0();
    final PlanNode rootPlanNode = instancePlanMaker.makeInnerSegmentPlan(_indexSegment, brokerRequest);
    rootPlanNode.showTree("");
    // UAggregationGroupByOperator operator = (UAggregationGroupByOperator) rootPlanNode.run();
    final MAggregationGroupByOperator operator = (MAggregationGroupByOperator) rootPlanNode.run();
    final IntermediateResultsBlock resultBlock = (IntermediateResultsBlock) operator.nextBlock();
    LOGGER.info("RunningTime : {}", resultBlock.getTimeUsedMs());
    LOGGER.info("NumDocsScanned : {}", resultBlock.getNumDocsScanned());
    LOGGER.info("TotalDocs : {}", resultBlock.getTotalDocs());
    Assert.assertEquals(resultBlock.getNumDocsScanned(), 582);
    Assert.assertEquals(resultBlock.getTotalDocs(), 10001);

    final AggregationGroupByOperatorService aggregationGroupByOperatorService =
        new AggregationGroupByOperatorService(_aggregationInfos, brokerRequest.getGroupBy());

    final Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    instanceResponseMap.put(new ServerInstance("localhost:0000"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:1111"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:2222"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:3333"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:4444"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:5555"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:6666"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:7777"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:8888"), resultBlock.getAggregationGroupByResultDataTable());
    instanceResponseMap.put(new ServerInstance("localhost:9999"), resultBlock.getAggregationGroupByResultDataTable());
    final List<Map<String, Serializable>> reducedResults =
        aggregationGroupByOperatorService.reduceGroupByOperators(instanceResponseMap);
    //    LOGGER.info("********************************");
    //    for (int i = 0; i < reducedResults.size(); ++i) {
    //      Map<String, Serializable> groupByResult = reducedResults.get(i);
    //      LOGGER.info(groupByResult);
    //    }
    //    LOGGER.info("********************************");
    final List<JSONObject> jsonResult = aggregationGroupByOperatorService.renderGroupByOperators(reducedResults);
    LOGGER.info("Result: {}", jsonResult);
  }

  @Test
  public void testInterSegmentAggregationGroupByPlanMakerAndRun() throws Exception {
    final int numSegments = 20;
    setupSegmentList(numSegments);
    final PlanMaker instancePlanMaker = new InstancePlanMakerImplV0();
    final BrokerRequest brokerRequest = getAggregationGroupByNoFilterBrokerRequest();
    final ExecutorService executorService = Executors.newCachedThreadPool(new NamedThreadFactory("test-plan-maker"));
    final Plan globalPlan =
        instancePlanMaker.makeInterSegmentPlan(_indexSegmentList, brokerRequest, executorService, 150000);
    globalPlan.print();
    globalPlan.execute();
    final DataTable instanceResponse = globalPlan.getInstanceResponse();
    LOGGER.info("Instance Response : {}", instanceResponse);

    final DefaultReduceService defaultReduceService = new DefaultReduceService();
    final Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    instanceResponseMap.put(new ServerInstance("localhost:0000"), instanceResponse);
    final BrokerResponse brokerResponse = defaultReduceService.reduceOnDataTable(brokerRequest, instanceResponseMap);
    LOGGER.info("Result: {} ", new JSONArray(brokerResponse.getAggregationResults()));
    LOGGER.info("Time used : {}", brokerResponse.getTimeUsedMs());
    assertBrokerResponse(numSegments, brokerResponse);
  }

  @Test
  public void testEmptyQueryResultsForInterSegmentAggregationGroupBy() throws Exception {
    final int numSegments = 20;
    setupSegmentList(numSegments);
    final PlanMaker instancePlanMaker = new InstancePlanMakerImplV0();
    final BrokerRequest brokerRequest = getAggregationGroupByWithEmptyFilterBrokerRequest();
    final ExecutorService executorService = Executors.newCachedThreadPool(new NamedThreadFactory("test-plan-maker"));
    final Plan globalPlan =
        instancePlanMaker.makeInterSegmentPlan(_indexSegmentList, brokerRequest, executorService, 150000);
    globalPlan.print();
    globalPlan.execute();
    final DataTable instanceResponse = globalPlan.getInstanceResponse();
    LOGGER.info("Instance Response : {}", instanceResponse);

    final DefaultReduceService defaultReduceService = new DefaultReduceService();
    final Map<ServerInstance, DataTable> instanceResponseMap = new HashMap<ServerInstance, DataTable>();
    instanceResponseMap.put(new ServerInstance("localhost:0000"), instanceResponse);
    final BrokerResponse brokerResponse = defaultReduceService.reduceOnDataTable(brokerRequest, instanceResponseMap);
    LOGGER.info("Response : {}", new JSONArray(brokerResponse.getAggregationResults()));
    LOGGER.info("Time used : {}", brokerResponse.getTimeUsedMs());
    assertEmptyBrokerResponse(brokerResponse);
  }

  private void assertBrokerResponse(int numSegments, BrokerResponse brokerResponse) throws JSONException {
    Assert.assertEquals(10001 * numSegments, brokerResponse.getNumDocsScanned());
    Assert.assertEquals(_numAggregations, brokerResponse.getAggregationResults().size());
    for (int i = 0; i < _numAggregations; ++i) {
      Assert.assertEquals("[\"column11\",\"column10\"]", brokerResponse.getAggregationResults()
          .get(i).getJSONArray("groupByColumns").toString());
      Assert.assertEquals(15, brokerResponse.getAggregationResults().get(i).getJSONArray("groupByResult").length());
    }

    // Assertion on Count
    Assert.assertEquals("count_star", brokerResponse.getAggregationResults().get(0).getString("function").toString());
    Assert.assertEquals("sum_met_impressionCount", brokerResponse.getAggregationResults().get(1).getString("function")
        .toString());
    Assert.assertEquals("max_met_impressionCount", brokerResponse.getAggregationResults().get(2).getString("function")
        .toString());
    Assert.assertEquals("min_met_impressionCount", brokerResponse.getAggregationResults().get(3).getString("function")
        .toString());
    Assert.assertEquals("avg_met_impressionCount", brokerResponse.getAggregationResults().get(4).getString("function")
        .toString());
    Assert.assertEquals("distinctCount_column12",
        brokerResponse.getAggregationResults().get(5).getString("function").toString());

    // Assertion on Aggregation Results
    LOGGER.info("brokerResponse = {}", brokerResponse);
    final List<double[]> aggregationResult = getAggregationResult(numSegments);
    final List<String[]> groupByResult = getGroupResult();
    for (int j = 0; j < _numAggregations; ++j) {
      final double[] aggResult = aggregationResult.get(j);
      final String[] groupResult = groupByResult.get(j);
      for (int i = 0; i < 15; ++i) {
        Assert.assertEquals(0, DoubleComparisonUtil.defaultDoubleCompare(aggResult[i],
            brokerResponse.getAggregationResults().get(j).getJSONArray("groupByResult").getJSONObject(i).getDouble("value")));
        if ((i < 14 && aggResult[i] == aggResult[i + 1]) || (i > 0 && aggResult[i] == aggResult[i - 1])) {
          //do nothing, as we have multiple groups within same value.
        } else {
          Assert.assertEquals(
              groupResult[i],
              brokerResponse.getAggregationResults().get(j).getJSONArray("groupByResult").getJSONObject(i)
                  .getString("group"));
        }
      }
    }
  }

  private void assertEmptyBrokerResponse(BrokerResponse brokerResponse) throws JSONException {
    Assert.assertEquals(0, brokerResponse.getNumDocsScanned());
    Assert.assertEquals(_numAggregations, brokerResponse.getAggregationResults().size());
    for (int i = 0; i < _numAggregations; ++i) {
      Assert.assertEquals("[\"column11\",\"column10\"]", brokerResponse.getAggregationResults()
          .get(i).getJSONArray("groupByColumns").toString());
      Assert.assertEquals(0, brokerResponse.getAggregationResults().get(i).getJSONArray("groupByResult").length());
    }

    // Assertion on Count
    Assert.assertEquals("count_star", brokerResponse.getAggregationResults().get(0).getString("function").toString());
    Assert.assertEquals("sum_met_impressionCount", brokerResponse.getAggregationResults().get(1).getString("function")
        .toString());
    Assert.assertEquals("max_met_impressionCount", brokerResponse.getAggregationResults().get(2).getString("function")
        .toString());
    Assert.assertEquals("min_met_impressionCount", brokerResponse.getAggregationResults().get(3).getString("function")
        .toString());
    Assert.assertEquals("avg_met_impressionCount", brokerResponse.getAggregationResults().get(4).getString("function")
        .toString());
    Assert.assertEquals("distinctCount_column12",
        brokerResponse.getAggregationResults().get(5).getString("function").toString());
  }

  private static List<double[]> getAggregationResult(int numSegments) {
    final List<double[]> aggregationResultList = new ArrayList<double[]>();
    aggregationResultList.add(getCountResult(numSegments));
    aggregationResultList.add(getSumResult(numSegments));
    aggregationResultList.add(getMaxResult());
    aggregationResultList.add(getMinResult());
    aggregationResultList.add(getAvgResult());
    aggregationResultList.add(getDistinctCountResult());
    return aggregationResultList;
  }

  private static List<String[]> getGroupResult() {
    final List<String[]> groupResults = new ArrayList<String[]>();
    groupResults.add(getCountGroupResult());
    groupResults.add(getSumGroupResult());
    groupResults.add(getMaxGroupResult());
    groupResults.add(getMinGroupResult());
    groupResults.add(getAvgGroupResult());
    groupResults.add(getDistinctCountGroupResult());
    return groupResults;
  }

  private static double[] getCountResult(int numSegments) {
    return new double[] { 1450 * numSegments, 620 * numSegments, 517 * numSegments, 422 * numSegments, 365 * numSegments, 340 * numSegments, 321 * numSegments, 296 * numSegments, 286 * numSegments, 273 * numSegments, 271 * numSegments, 268 * numSegments, 234 * numSegments, 210 * numSegments, 208 * numSegments };
  }

  private static String[] getCountGroupResult() {
    return new String[] { "[\"i\",\"\"]", "[\"D\",\"\"]", "[\"i\",\"CqC\"]", "[\"i\",\"QMl\"]", "[\"i\",\"bVnY\"]", "[\"i\",\"iV\"]", "[\"i\",\"zZe\"]", "[\"i\",\"xDLG\"]", "[\"i\",\"VsKz\"]", "[\"i\",\"mNh\"]", "[\"i\",\"ez\"]", "[\"i\",\"rNcu\"]", "[\"i\",\"EXYv\"]", "[\"i\",\"gpyD\"]", "[\"i\",\"yhq\"]" };
  }

  private static double[] getSumResult(int numSegments) {
    return new double[] { 194232989695956150000000.00000, 82874083725452570000000.00000, 69188102307666020000000.00000, 57011594945268800000000.00000, 49669069292549060000000.00000, 45658425435674350000000.00000, 42733154649942075000000.00000, 39374565823833550000000.00000, 38376043393352970000000.00000, 36944406922141550000000.00000, 36562112604244086000000.00000, 36141768458849143000000.00000, 31259578136918286000000.00000, 27679187240218786000000.00000, 27524721980723073000000.00000 };
  }

  private static String[] getSumGroupResult() {
    return new String[] { "[\"i\",\"\"]", "[\"D\",\"\"]", "[\"i\",\"CqC\"]", "[\"i\",\"QMl\"]", "[\"i\",\"bVnY\"]", "[\"i\",\"iV\"]", "[\"i\",\"zZe\"]", "[\"i\",\"xDLG\"]", "[\"i\",\"VsKz\"]", "[\"i\",\"mNh\"]", "[\"i\",\"ez\"]", "[\"i\",\"rNcu\"]", "[\"i\",\"EXYv\"]", "[\"i\",\"yhq\"]", "[\"i\",\"gpyD\"]" };
  }

  private static double[] getMaxResult() {
    return new double[] { 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000, 8637957270245934100.00000 };
  }

  private static String[] getMaxGroupResult() {
    return new String[] { "[\"i\",\"yH\"]", "[\"U\",\"mNh\"]", "[\"i\",\"OYMU\"]", "[\"D\",\"opm\"]", "[\"i\",\"ZQa\"]", "[\"D\",\"Gac\"]", "[\"i\",\"gpyD\"]", "[\"D\",\"Pcb\"]", "[\"i\",\"mNh\"]", "[\"U\",\"LjAS\"]", "[\"U\",\"bVnY\"]", "[\"D\",\"iV\"]", "[\"D\",\"aN\"]", "[\"U\",\"Vj\"]", "[\"D\",\"KsKZ\"]" };
  }

  private static double[] getMinResult() {
    return new double[] { 614819680033322500.00000, 614819680033322500.00000, 614819680033322500.00000, 614819680033322500.00000, 614819680033322500.00000, 614819680033322500.00000, 614819680033322500.00000, 614819680033322500.00000, 1048718684474966140.00000, 1048718684474966140.00000, 1048718684474966140.00000, 3703896352903212000.00000, 3703896352903212000.00000, 3703896352903212000.00000, 3703896352903212000.00000 };
  }

  private static String[] getMinGroupResult() {
    return new String[] { "[\"D\",\"Gac\"]", "[\"i\",\"mNh\"]", "[\"i\",\"VsKz\"]", "[\"D\",\"\"]", "[\"i\",\"yhq\"]", "[\"D\",\"CqC\"]", "[\"U\",\"\"]", "[\"i\",\"jb\"]", "[\"D\",\"bVnY\"]", "[\"i\",\"\"]", "[\"i\",\"QMl\"]", "[\"i\",\"Pcb\"]", "[\"i\",\"EXYv\"]", "[\"i\",\"bVnY\"]", "[\"i\",\"zZe\"]" };
  }

  private static double[] getAvgResult() {
    return new double[] { 7768390271561314300.00000, 7215319188094814200.00000, 7105513810764889100.00000, 7094438547504759800.00000, 7004199482369404900.00000, 6991851055242935300.00000, 6987779156890090500.00000, 6973627660796153900.00000, 6970558938737374200.00000, 6964262042984379400.00000, 6912897688920598500.00000, 6906152143309600800.00000, 6888134675143909400.00000, 6880505863259489300.00000, 6878447250928267300.00000 };
  }

  private static String[] getAvgGroupResult() {
    return new String[] { "[\"U\",\"yhq\"]", "[\"U\",\"mNh\"]", "[\"U\",\"Vj\"]", "[\"U\",\"OYMU\"]", "[\"U\",\"zZe\"]", "[\"U\",\"jb\"]", "[\"D\",\"aN\"]", "[\"U\",\"bVnY\"]", "[\"U\",\"iV\"]", "[\"i\",\"LjAS\"]", "[\"D\",\"xDLG\"]", "[\"U\",\"EXYv\"]", "[\"D\",\"iV\"]", "[\"D\",\"Gac\"]", "[\"D\",\"QMl\"]" };
  }

  private static double[] getDistinctCountResult() {
    return new double[] { 128, 109, 100, 99, 84, 81, 77, 76, 75, 74, 71, 67, 67, 62, 57 };
  }

  private static String[] getDistinctCountGroupResult() {
    return new String[] { "[\"i\",\"\"]", "[\"D\",\"\"]", "[\"i\",\"zZe\"]", "[\"i\",\"QMl\"]", "[\"i\",\"bVnY\"]", "[\"i\",\"iV\"]", "[\"i\",\"VsKz\"]", "[\"i\",\"CqC\"]", "[\"i\",\"EXYv\"]", "[\"i\",\"xDLG\"]", "[\"i\",\"yhq\"]", "[\"U\",\"\"]", "[\"D\",\"EXYv\"]", "[\"D\",\"LjAS\"]", "[\"i\",\"rNcu\"]" };
  }

  private static BrokerRequest getAggregationGroupByNoFilterBrokerRequest() {
    final BrokerRequest brokerRequest = new BrokerRequest();
    final List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(getCountAggregationInfo());
    aggregationsInfo.add(getSumAggregationInfo());
    aggregationsInfo.add(getMaxAggregationInfo());
    aggregationsInfo.add(getMinAggregationInfo());
    aggregationsInfo.add(getAvgAggregationInfo());
    aggregationsInfo.add(getDistinctCountAggregationInfo("column12"));
    brokerRequest.setAggregationsInfo(aggregationsInfo);
    brokerRequest.setGroupBy(getGroupBy());
    return brokerRequest;
  }

  private static List<AggregationInfo> getAggregationsInfo() {
    final List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(getCountAggregationInfo());
    aggregationsInfo.add(getSumAggregationInfo());
    aggregationsInfo.add(getMaxAggregationInfo());
    aggregationsInfo.add(getMinAggregationInfo());
    aggregationsInfo.add(getAvgAggregationInfo());
    aggregationsInfo.add(getDistinctCountAggregationInfo("column12"));
    return aggregationsInfo;
  }

  private static Map<String, DataSource> getDataSourceMap() {
    final Map<String, DataSource> dataSourceMap = new HashMap<String, DataSource>();
    dataSourceMap.put("column11", _indexSegment.getDataSource("column11"));
    dataSourceMap.put("column12", _indexSegment.getDataSource("column12"));
    dataSourceMap.put("met_impressionCount", _indexSegment.getDataSource("met_impressionCount"));
    return dataSourceMap;
  }

  private static AggregationInfo getCountAggregationInfo() {
    final String type = "count";
    final Map<String, String> params = new HashMap<String, String>();
    params.put("column", "*");
    final AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private static AggregationInfo getSumAggregationInfo() {
    final String type = "sum";
    final Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met_impressionCount");
    final AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private static AggregationInfo getMaxAggregationInfo() {
    final String type = "max";
    final Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met_impressionCount");
    final AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private static AggregationInfo getMinAggregationInfo() {
    final String type = "min";
    final Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met_impressionCount");
    final AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private static AggregationInfo getAvgAggregationInfo() {
    final String type = "avg";
    final Map<String, String> params = new HashMap<String, String>();
    params.put("column", "met_impressionCount");
    final AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private static AggregationInfo getDistinctCountAggregationInfo(String dim) {
    final String type = "distinctCount";
    final Map<String, String> params = new HashMap<String, String>();
    params.put("column", dim);

    final AggregationInfo aggregationInfo = new AggregationInfo();
    aggregationInfo.setAggregationType(type);
    aggregationInfo.setAggregationParams(params);
    return aggregationInfo;
  }

  private static GroupBy getGroupBy() {
    final GroupBy groupBy = new GroupBy();
    final List<String> columns = new ArrayList<String>();
    columns.add("column11");
    columns.add("column10");
    groupBy.setColumns(columns);
    groupBy.setTopN(15);
    return groupBy;
  }

  private static BrokerRequest getAggregationGroupByWithFilterBrokerRequest() {
    final BrokerRequest brokerRequest = new BrokerRequest();
    final List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(getCountAggregationInfo());
    aggregationsInfo.add(getSumAggregationInfo());
    aggregationsInfo.add(getMaxAggregationInfo());
    aggregationsInfo.add(getMinAggregationInfo());
    aggregationsInfo.add(getAvgAggregationInfo());
    aggregationsInfo.add(getDistinctCountAggregationInfo("column12"));
    brokerRequest.setAggregationsInfo(aggregationsInfo);
    brokerRequest.setGroupBy(getGroupBy());
    setFilterQuery(brokerRequest);
    return brokerRequest;
  }

  private static BrokerRequest setFilterQuery(BrokerRequest brokerRequest) {
    FilterQueryTree filterQueryTree;
    final String filterColumn = "column11";
    final String filterVal = "U";
    if (filterColumn.contains(",")) {
      final String[] filterColumns = filterColumn.split(",");
      final String[] filterValues = filterVal.split(",");
      final List<FilterQueryTree> nested = new ArrayList<FilterQueryTree>();
      for (int i = 0; i < filterColumns.length; i++) {

        final List<String> vals = new ArrayList<String>();
        vals.add(filterValues[i]);
        final FilterQueryTree d = new FilterQueryTree(i + 1, filterColumns[i], vals, FilterOperator.EQUALITY, null);
        nested.add(d);
      }
      filterQueryTree = new FilterQueryTree(0, null, null, FilterOperator.AND, nested);
    } else {
      final List<String> vals = new ArrayList<String>();
      vals.add(filterVal);
      filterQueryTree = new FilterQueryTree(0, filterColumn, vals, FilterOperator.EQUALITY, null);
    }
    RequestUtils.generateFilterFromTree(filterQueryTree, brokerRequest);
    return brokerRequest;
  }

  private static BrokerRequest getAggregationGroupByWithEmptyFilterBrokerRequest() {
    final BrokerRequest brokerRequest = new BrokerRequest();
    final List<AggregationInfo> aggregationsInfo = new ArrayList<AggregationInfo>();
    aggregationsInfo.add(getCountAggregationInfo());
    aggregationsInfo.add(getSumAggregationInfo());
    aggregationsInfo.add(getMaxAggregationInfo());
    aggregationsInfo.add(getMinAggregationInfo());
    aggregationsInfo.add(getAvgAggregationInfo());
    aggregationsInfo.add(getDistinctCountAggregationInfo("column12"));
    brokerRequest.setAggregationsInfo(aggregationsInfo);
    brokerRequest.setGroupBy(getGroupBy());
    setEmptyFilterQuery(brokerRequest);
    return brokerRequest;
  }

  private static BrokerRequest setEmptyFilterQuery(BrokerRequest brokerRequest) {
    FilterQueryTree filterQueryTree;
    final String filterColumn = "column11";
    final String filterVal = "uuuu";
    if (filterColumn.contains(",")) {
      final String[] filterColumns = filterColumn.split(",");
      final String[] filterValues = filterVal.split(",");
      final List<FilterQueryTree> nested = new ArrayList<FilterQueryTree>();
      for (int i = 0; i < filterColumns.length; i++) {

        final List<String> vals = new ArrayList<String>();
        vals.add(filterValues[i]);
        final FilterQueryTree d = new FilterQueryTree(i + 1, filterColumns[i], vals, FilterOperator.EQUALITY, null);
        nested.add(d);
      }
      filterQueryTree = new FilterQueryTree(0, null, null, FilterOperator.AND, nested);
    } else {
      final List<String> vals = new ArrayList<String>();
      vals.add(filterVal);
      filterQueryTree = new FilterQueryTree(0, filterColumn, vals, FilterOperator.EQUALITY, null);
    }
    RequestUtils.generateFilterFromTree(filterQueryTree, brokerRequest);
    return brokerRequest;
  }

}
