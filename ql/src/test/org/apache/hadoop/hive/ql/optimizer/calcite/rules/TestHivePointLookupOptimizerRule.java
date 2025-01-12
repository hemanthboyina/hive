/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.optimizer.calcite.rules;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.RelBuilder;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.optimizer.calcite.HiveRelFactories;
import org.apache.hadoop.hive.ql.optimizer.calcite.RelOptHiveTable;
import org.apache.hadoop.hive.ql.optimizer.calcite.reloperators.HiveFilter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;

@RunWith(MockitoJUnitRunner.class)
public class TestHivePointLookupOptimizerRule {

  @Mock
  private RelOptSchema schemaMock;
  @Mock
  RelOptHiveTable tableMock;
  @Mock
  Table hiveTableMDMock;

  private HepPlanner planner;
  private RelBuilder builder;

  @SuppressWarnings("unused")
  private static class MyRecord {
    public int f1;
    public int f2;
    public int f3;
    public double f4;
  }

  @Before
  public void before() {
    HepProgramBuilder programBuilder = new HepProgramBuilder();
    programBuilder.addRuleInstance(new HivePointLookupOptimizerRule.FilterCondition(2));

    planner = new HepPlanner(programBuilder.build());

    JavaTypeFactoryImpl typeFactory = new JavaTypeFactoryImpl();
    RexBuilder rexBuilder = new RexBuilder(typeFactory);
    final RelOptCluster optCluster = RelOptCluster.create(planner, rexBuilder);
    RelDataType rowTypeMock = typeFactory.createStructType(MyRecord.class);
    doReturn(rowTypeMock).when(tableMock).getRowType();
    LogicalTableScan tableScan = LogicalTableScan.create(optCluster, tableMock, Collections.emptyList());
    doReturn(tableScan).when(tableMock).toRel(ArgumentMatchers.any());
    doReturn(tableMock).when(schemaMock).getTableForMember(any());
    lenient().doReturn(hiveTableMDMock).when(tableMock).getHiveTableMD();

    builder = HiveRelFactories.HIVE_BUILDER.create(optCluster, schemaMock);

  }

  public RexNode or(RexNode... args) {
    return builder.call(SqlStdOperatorTable.OR, args);
  }

  public RexNode and(RexNode... args) {
    return builder.call(SqlStdOperatorTable.AND, args);
  }

  public RexNode eq(String field, Number value) {
    return builder.call(SqlStdOperatorTable.EQUALS,
        builder.field(field), builder.literal(value));
  }

  @Test
  public void testSimpleCase() {

    // @formatter:off
    final RelNode basePlan = builder
          .scan("t")
          .filter(
              and(
                or(
                    eq("f1",1),
                    eq("f1",2)
                    ),
                or(
                    eq("f2",3),
                    eq("f2",4)
                    )
                )
              )
          .build();
    // @formatter:on

    planner.setRoot(basePlan);
    RelNode optimizedRelNode = planner.findBestExp();

    HiveFilter filter = (HiveFilter) optimizedRelNode;
    RexNode condition = filter.getCondition();
    assertEquals("AND(IN($0, 1, 2), IN($1, 3, 4))", condition.toString());
  }

  @Test
  public void testInExprsMergedSingleOverlap() {

    // @formatter:off
    final RelNode basePlan = builder
        .scan("t")
        .filter(
            and(
                or(
                    eq("f1",1),
                    eq("f1",2)
                ),
                or(
                    eq("f1",1),
                    eq("f1",3)
                )
            )
        )
        .build();
    // @formatter:on

    planner.setRoot(basePlan);
    RelNode optimizedRelNode = planner.findBestExp();

    HiveFilter filter = (HiveFilter) optimizedRelNode;
    RexNode condition = filter.getCondition();
    assertEquals("=($0, 1)", condition.toString());
  }

  @Test
  public void testInExprsAndEqualsMerged() {

    // @formatter:off
    final RelNode basePlan = builder
        .scan("t")
        .filter(
            and(
                or(
                    eq("f1",1),
                    eq("f1",2)
                ),
                or(
                    eq("f1",1),
                    eq("f1",3)
                ),
                eq("f1",1)
            )
        )
        .build();
    // @formatter:on

    planner.setRoot(basePlan);
    RelNode optimizedRelNode = planner.findBestExp();

    HiveFilter filter = (HiveFilter) optimizedRelNode;
    RexNode condition = filter.getCondition();
    assertEquals("=($0, 1)", condition.toString());
  }

  @Test
  public void testInExprsMergedMultipleOverlap() {

    // @formatter:off
    final RelNode basePlan = builder
        .scan("t")
        .filter(
            and(
                or(
                    eq("f1",1),
                    eq("f1",2),
                    eq("f1",4),
                    eq("f1",3)
                ),
                or(
                    eq("f1",5),
                    eq("f1",1),
                    eq("f1",2),
                    eq("f1",3)
                )
            )
        )
        .build();
    // @formatter:on

    planner.setRoot(basePlan);
    RelNode optimizedRelNode = planner.findBestExp();

    HiveFilter filter = (HiveFilter) optimizedRelNode;
    RexNode condition = filter.getCondition();
    assertEquals("IN($0, 1, 2, 3)", condition.toString());
  }

  @Test
  public void testCaseWithConstantsOfDifferentType() {

    // @formatter:off
    final RelNode basePlan = builder
        .scan("t")
        .filter(
            and(
                or(
                    eq("f1",1),
                    eq("f1",2)
                ),
                eq("f1", 1.0),
                or(
                    eq("f4",3.0),
                    eq("f4",4.1)
                )
            )
        )
        .build();
    // @formatter:on

    planner.setRoot(basePlan);
    RelNode optimizedRelNode = planner.findBestExp();

    HiveFilter filter = (HiveFilter) optimizedRelNode;
    RexNode condition = filter.getCondition();
    // ideally the result would be AND(=($0, 1), IN($3, 3.0E0:DOUBLE, 4.1E0:DOUBLE)), but we
    // don't try to compare constants of different type for the same column, even if comparable
    assertEquals("AND(IN($0, 1, 2), =($0, 1.0E0:DOUBLE), IN($3, 3.0E0:DOUBLE, 4.1E0:DOUBLE))",
        condition.toString());
  }

  @Test
  public void testCaseInAndEqualsWithConstantsOfDifferentType() {

    // @formatter:off
    final RelNode basePlan = builder
        .scan("t")
        .filter(
            and(
                or(
                    eq("f1",1),
                    eq("f1",2)
                ),
                eq("f1",1),
                or(
                    eq("f4",3.0),
                    eq("f4",4.1)
                ),
                eq("f4",4.1)
            )
        )
        .build();
    // @formatter:on

    planner.setRoot(basePlan);
    RelNode optimizedRelNode = planner.findBestExp();

    HiveFilter filter = (HiveFilter) optimizedRelNode;
    RexNode condition = filter.getCondition();
    assertEquals("AND(=($0, 1), =($3, 4.1E0:DOUBLE))", condition.toString());
  }

  @Test
  public void testSimpleStructCase() {

    // @formatter:off
    final RelNode basePlan = builder
          .scan("t")
          .filter(
              or(
                  and( eq("f1",1),eq("f2",1)),
                  and( eq("f1",2),eq("f2",2))
                  )
              )
          .build();
    // @formatter:on

    planner.setRoot(basePlan);
    RelNode optimizedRelNode = planner.findBestExp();

    HiveFilter filter = (HiveFilter) optimizedRelNode;
    RexNode condition = filter.getCondition();
    assertEquals("IN(ROW($0, $1), ROW(1, 1), ROW(2, 2))", condition.toString());
  }

  /** Despite the fact that f2=99 is there...the extraction should happen */
  @Test
  public void testObscuredSimple() {

    // @formatter:off
    final RelNode basePlan = builder
          .scan("t")
          .filter(
              or(
                  eq("f2",99),
                  eq("f1",1),
                  eq("f1",2)
                  )
              )
          .build();
    // @formatter:on

    planner.setRoot(basePlan);
    RelNode optimizedRelNode = planner.findBestExp();

    HiveFilter filter = (HiveFilter) optimizedRelNode;
    RexNode condition = filter.getCondition();
    System.out.println(condition);
    assertEquals("OR(IN($0, 1, 2), =($1, 99))", condition.toString());
  }

  /** Despite that extraction happen at a higher level; nested parts should also be handled */
  @Test
  public void testRecursionIsNotObstructed() {

    // @formatter:off
    final RelNode basePlan = builder
          .scan("t")
          .filter(
              and(
                or(
                    eq("f1",1),
                    eq("f1",2)
                    )
                ,
                or(
                    and(
                        or(eq("f2",1),eq("f2",2)),
                        or(eq("f3",1),eq("f3",2))
                        ),
                    and(
                        or(eq("f2",3),eq("f2",4)),
                        or(eq("f3",3),eq("f3",4))
                        )
                )
              ))
          .build();
    // @formatter:on

    planner.setRoot(basePlan);
    RelNode optimizedRelNode = planner.findBestExp();

    HiveFilter filter = (HiveFilter) optimizedRelNode;
    RexNode condition = filter.getCondition();
    System.out.println(condition);
    assertEquals("AND(IN($0, 1, 2), OR(AND(IN($1, 1, 2), IN($2, 1, 2)), "
            + "AND(IN($1, 3, 4), IN($2, 3, 4))))",
        condition.toString());
  }

}
