/**
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

package org.apache.hadoop.hive.ql.optimizer.optiq.stats;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

import net.hydromatic.optiq.BuiltinMethod;
import net.hydromatic.optiq.util.BitSets;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eigenbase.rel.FilterRelBase;
import org.eigenbase.rel.JoinRelBase;
import org.eigenbase.rel.JoinRelType;
import org.eigenbase.rel.ProjectRelBase;
import org.eigenbase.rel.RelNode;
import org.eigenbase.rel.RelVisitor;
import org.eigenbase.rel.TableAccessRelBase;
import org.eigenbase.rel.metadata.ReflectiveRelMetadataProvider;
import org.eigenbase.rel.metadata.RelMdRowCount;
import org.eigenbase.rel.metadata.RelMetadataProvider;
import org.eigenbase.rel.metadata.RelMetadataQuery;
import org.eigenbase.rel.rules.SemiJoinRel;
import org.eigenbase.relopt.RelOptUtil;
import org.eigenbase.rex.RexBuilder;
import org.eigenbase.rex.RexCall;
import org.eigenbase.rex.RexInputRef;
import org.eigenbase.rex.RexNode;
import org.eigenbase.rex.RexUtil;
import org.eigenbase.sql.fun.SqlStdOperatorTable;
import org.eigenbase.util.Holder;
import org.eigenbase.util.Pair;

public class HiveRelMdRowCount extends RelMdRowCount {

  protected static final Log LOG  = LogFactory.getLog(HiveRelMdRowCount.class.getName());


  public static final RelMetadataProvider SOURCE = ReflectiveRelMetadataProvider
      .reflectiveSource(BuiltinMethod.ROW_COUNT.method, new HiveRelMdRowCount());

  protected HiveRelMdRowCount() {
    super();
  }

  public Double getRowCount(JoinRelBase join) {
    PKFKRelationInfo pkfk = analyzeJoinForPKFK(join);
    if (pkfk != null) {
      double selectivity = (pkfk.pkInfo.selectivity * pkfk.ndvScalingFactor);
      selectivity = Math.min(1.0, selectivity);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Identified Primary - Foreign Key relation:");
        LOG.debug(RelOptUtil.toString(join));
        LOG.debug(pkfk);
      }
      return pkfk.fkInfo.rowCount * selectivity;
    }
    return join.getRows();
  }

  public Double getRowCount(SemiJoinRel rel) {
    PKFKRelationInfo pkfk = analyzeJoinForPKFK(rel);
    if (pkfk != null) {
      double selectivity = (pkfk.pkInfo.selectivity * pkfk.ndvScalingFactor);
      selectivity = Math.min(1.0, selectivity);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Identified Primary - Foreign Key relation:");
        LOG.debug(RelOptUtil.toString(rel));
        LOG.debug(pkfk);
      }
      return pkfk.fkInfo.rowCount * selectivity;
    }
    return super.getRowCount(rel);
  }

  static class PKFKRelationInfo {
    public final int fkSide;
    public final double ndvScalingFactor;
    public final FKSideInfo fkInfo;
    public final PKSideInfo pkInfo;
    public final boolean isPKSideSimple;

    PKFKRelationInfo(int fkSide,
        FKSideInfo fkInfo,
        PKSideInfo pkInfo,
        double ndvScalingFactor,
        boolean isPKSideSimple) {
      this.fkSide = fkSide;
      this.fkInfo = fkInfo;
      this.pkInfo = pkInfo;
      this.ndvScalingFactor = ndvScalingFactor;
      this.isPKSideSimple = isPKSideSimple;
    }

    public String toString() {
      return String.format(
          "Primary - Foreign Key join:\n\tfkSide = %d\n\tFKInfo:%s\n" +
          "\tPKInfo:%s\n\tisPKSideSimple:%s\n\tNDV Scaling Factor:%.2f\n",
          fkSide,
          fkInfo,
          pkInfo,
          isPKSideSimple,
          ndvScalingFactor);
    }
  }

  static class FKSideInfo {
    public final double rowCount;
    public final double distinctCount;
    public FKSideInfo(double rowCount, double distinctCount) {
      this.rowCount = rowCount;
      this.distinctCount = distinctCount;
    }

    public String toString() {
      return String.format("FKInfo(rowCount=%.2f,ndv=%.2f)", rowCount, distinctCount);
    }
  }

  static class PKSideInfo extends FKSideInfo {
    public final double selectivity;
    public PKSideInfo(double rowCount, double distinctCount, double selectivity) {
      super(rowCount, distinctCount);
      this.selectivity = selectivity;
    }

    public String toString() {
      return String.format("PKInfo(rowCount=%.2f,ndv=%.2f,selectivity=%.2f)", rowCount, distinctCount,selectivity);
    }
  }

  /*
   * For T1 join T2 on T1.x = T2.y if we identify 'y' s a key of T2 then we can
   * infer the join cardinality as: rowCount(T1) * selectivity(T2) i.e this is
   * like a SemiJoin where the T1(Fact side/FK side) is filtered by a factor
   * based on the Selectivity of the PK/Dim table side.
   *
   * 1. If both T1.x and T2.y are keys then use the larger one as the PK side.
   * 2. In case of outer Joins: a) The FK side should be the Null Preserving
   * side. It doesn't make sense to apply this heuristic in case of Dim loj Fact
   * or Fact roj Dim b) The selectivity factor applied on the Fact Table should
   * be 1.
   */
  public static PKFKRelationInfo analyzeJoinForPKFK(JoinRelBase joinRel) {

    RelNode left = joinRel.getInputs().get(0);
    RelNode right = joinRel.getInputs().get(1);

    final List<RexNode> initJoinFilters = RelOptUtil.conjunctions(joinRel
        .getCondition());

    /*
     * No joining condition.
     */
    if (initJoinFilters.isEmpty()) {
      return null;
    }

    List<RexNode> leftFilters = new ArrayList<RexNode>();
    List<RexNode> rightFilters = new ArrayList<RexNode>();
    List<RexNode> joinFilters = new ArrayList<RexNode>(initJoinFilters);
    final Holder<JoinRelType> joinTypeHolder = Holder.of(joinRel.getJoinType());

    // @todo: remove this. 8/28/14 hb
    // for now adding because RelOptUtil.classifyFilters has an assertion about
    // column counts that is not true for semiJoins.
    if (joinRel instanceof SemiJoinRel) {
      return null;
    }

    RelOptUtil.classifyFilters(joinRel, joinFilters, joinRel.getJoinType(),
        false, !joinRel.getJoinType().generatesNullsOnRight(), !joinRel
            .getJoinType().generatesNullsOnLeft(), joinFilters, leftFilters,
        rightFilters, joinTypeHolder, false);

    Pair<Integer, Integer> joinCols = canHandleJoin(joinRel, leftFilters,
        rightFilters, joinFilters);
    if (joinCols == null) {
      return null;
    }
    int leftColIdx = joinCols.left;
    int rightColIdx = joinCols.right;

    RexBuilder rexBuilder = joinRel.getCluster().getRexBuilder();
    RexNode leftPred = RexUtil
        .composeConjunction(rexBuilder, leftFilters, true);
    RexNode rightPred = RexUtil.composeConjunction(rexBuilder, rightFilters,
        true);
    BitSet lBitSet = BitSets.of(leftColIdx);
    BitSet rBitSet = BitSets.of(rightColIdx);

    /*
     * If the form is Dim loj F or Fact roj Dim or Dim semij Fact then return
     * null.
     */
    boolean leftIsKey = (joinRel.getJoinType() == JoinRelType.INNER || joinRel
        .getJoinType() == JoinRelType.RIGHT)
        && !(joinRel instanceof SemiJoinRel) && isKey(lBitSet, left);
    boolean rightIsKey = (joinRel.getJoinType() == JoinRelType.INNER || joinRel
        .getJoinType() == JoinRelType.LEFT) && isKey(rBitSet, right);

    if (!leftIsKey && !rightIsKey) {
      return null;
    }

    double leftRowCount = RelMetadataQuery.getRowCount(left)
        * RelMetadataQuery.getSelectivity(left, leftPred);
    double rightRowCount = RelMetadataQuery.getRowCount(right)
        * RelMetadataQuery.getSelectivity(right, rightPred);

    if (leftIsKey && rightIsKey) {
      if (rightRowCount < leftRowCount) {
        leftIsKey = false;
      }
    }

    int pkSide = leftIsKey ? 0 : rightIsKey ? 1 : -1;

    boolean isPKSideSimpleTree = pkSide != -1 ? 
        IsSimpleTreeOnJoinKey.check(
            pkSide == 0 ? left : right,
            pkSide == 0 ? leftColIdx : rightColIdx) : false;

   double leftNDV = isPKSideSimpleTree ? RelMetadataQuery.getDistinctRowCount(left, lBitSet, leftPred) : -1;
   double rightNDV = isPKSideSimpleTree ? RelMetadataQuery.getDistinctRowCount(right, rBitSet, rightPred) : -1;

   /*
    * If the ndv of the PK - FK side don't match, and the PK side is a filter
    * on the Key column then scale the NDV on the FK side.
    *
    * As described by Peter Boncz: http://databasearchitects.blogspot.com/
    * in such cases we can be off by a large margin in the Join cardinality
    * estimate. The e.g. he provides is on the join of StoreSales and DateDim
    * on the TPCDS dataset. Since the DateDim is populated for 20 years into
    * the future, while the StoreSales only has 5 years worth of data, there
    * are 40 times fewer distinct dates in StoreSales.
    *
    * In general it is hard to infer the range for the foreign key on an
    * arbitrary expression. For e.g. the NDV for DayofWeek is the same
    * irrespective of NDV on the number of unique days, whereas the
    * NDV of Quarters has the same ratio as the NDV on the keys.
    *
    * But for expressions that apply only on columns that have the same NDV
    * as the key (implying that they are alternate keys) we can apply the
    * ratio. So in the case of StoreSales - DateDim joins for predicate on the
    * d_date column we can apply the scaling factor.
    */
   double ndvScalingFactor = 1.0;
   if ( isPKSideSimpleTree ) {
     ndvScalingFactor = pkSide == 0 ? leftNDV/rightNDV : rightNDV / leftNDV;
   }

    if (pkSide == 0) {
      FKSideInfo fkInfo = new FKSideInfo(rightRowCount,
          rightNDV);
      PKSideInfo pkInfo = new PKSideInfo(leftRowCount,
          leftNDV,
          joinRel.getJoinType().generatesNullsOnRight() ? 1.0 :
            RelMetadataQuery.getSelectivity(left, leftPred));

      return new PKFKRelationInfo(1, fkInfo, pkInfo, ndvScalingFactor, isPKSideSimpleTree);
    }

    if (pkSide == 1) {
      FKSideInfo fkInfo = new FKSideInfo(leftRowCount,
          leftNDV);
      PKSideInfo pkInfo = new PKSideInfo(rightRowCount,
          rightNDV,
          joinRel.getJoinType().generatesNullsOnLeft() ? 1.0 :
            RelMetadataQuery.getSelectivity(right, rightPred));

      return new PKFKRelationInfo(1, fkInfo, pkInfo, ndvScalingFactor, isPKSideSimpleTree);
    }

    return null;
  }

  private static boolean isKey(BitSet c, RelNode rel) {
    boolean isKey = false;
    Set<BitSet> keys = RelMetadataQuery.getUniqueKeys(rel);
    if (keys != null) {
      for (BitSet key : keys) {
        if (key.equals(c)) {
          isKey = true;
          break;
        }
      }
    }
    return isKey;
  }

  /*
   * 1. Join condition must be an Equality Predicate.
   * 2. both sides must reference 1 column.
   * 3. If needed flip the columns.
   */
  private static Pair<Integer, Integer> canHandleJoin(JoinRelBase joinRel,
      List<RexNode> leftFilters, List<RexNode> rightFilters,
      List<RexNode> joinFilters) {

    /*
     * If after classifying filters there is more than 1 joining predicate, we
     * don't handle this. Return null.
     */
    if (joinFilters.size() != 1) {
      return null;
    }

    RexNode joinCond = joinFilters.get(0);

    int leftColIdx;
    int rightColIdx;

    if (!(joinCond instanceof RexCall)) {
      return null;
    }

    if (((RexCall) joinCond).getOperator() != SqlStdOperatorTable.EQUALS) {
      return null;
    }

    BitSet leftCols = RelOptUtil.InputFinder.bits(((RexCall) joinCond).getOperands().get(0));
    BitSet rightCols = RelOptUtil.InputFinder.bits(((RexCall) joinCond).getOperands().get(1));

    if (leftCols.cardinality() != 1 || rightCols.cardinality() != 1 ) {
      return null;
    }

    int nFieldsLeft = joinRel.getLeft().getRowType().getFieldList().size();
    int nFieldsRight = joinRel.getRight().getRowType().getFieldList().size();
    int nSysFields = joinRel.getSystemFieldList().size();
    BitSet rightFieldsBitSet = BitSets.range(nSysFields + nFieldsLeft,
        nSysFields + nFieldsLeft + nFieldsRight);
    /*
     * flip column references if join condition specified in reverse order to
     * join sources.
     */
    if (BitSets.contains(rightFieldsBitSet, leftCols)) {
      BitSet t = leftCols;
      leftCols = rightCols;
      rightCols = t;
    }

    leftColIdx = leftCols.nextSetBit(0) - nSysFields;
    rightColIdx = rightCols.nextSetBit(0) - (nSysFields + nFieldsLeft);

    return new Pair<Integer, Integer>(leftColIdx, rightColIdx);
  }

  private static class IsSimpleTreeOnJoinKey extends RelVisitor {

    int joinKey;
    boolean simpleTree;

    static boolean check(RelNode r, int joinKey) {
      IsSimpleTreeOnJoinKey v = new IsSimpleTreeOnJoinKey(joinKey);
      v.go(r);
      return v.simpleTree;
    }

    IsSimpleTreeOnJoinKey(int joinKey) {
      super();
      this.joinKey = joinKey;
      simpleTree = true;
    }

    @Override
    public void visit(RelNode node, int ordinal, RelNode parent) {

      if (node instanceof TableAccessRelBase) {
        simpleTree = true;
      } else if (node instanceof ProjectRelBase) {
        simpleTree = isSimple((ProjectRelBase) node);
      } else if (node instanceof FilterRelBase) {
        simpleTree = isSimple((FilterRelBase) node);
      } else {
        simpleTree = false;
      }

      if (simpleTree) {
        super.visit(node, ordinal, parent);
      }
    }

    private boolean isSimple(ProjectRelBase project) {
      RexNode r = project.getProjects().get(joinKey);
      if (r instanceof RexInputRef) {
        joinKey = ((RexInputRef) r).getIndex();
        return true;
      }
      return false;
    }

    private boolean isSimple(FilterRelBase filter) {
      BitSet condBits = RelOptUtil.InputFinder.bits(filter.getCondition());
      return isKey(condBits, filter);
    }

  }

}