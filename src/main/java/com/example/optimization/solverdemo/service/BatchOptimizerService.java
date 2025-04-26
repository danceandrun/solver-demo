package com.example.optimization.solverdemo.service;

import com.example.optimization.solverdemo.entity.Batch;
import com.example.optimization.solverdemo.entity.PieceStep;
import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumExpr;
import ilog.cplex.IloCplex;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class BatchOptimizerService {
    // 配置参数(可调整)
    private static final int MAX_HEIGHT = 5500;
    private static final int MAX_WEIGHT = 120;
    private static final int CONVECTION_PLATE_HEIGHT = 70;
    private static final int CONVECTION_PLATE_WEIGHT = 1;
    private static final double MIN_THICKNESS_FIRST_TWO = 0.7;
    private static final double MAX_THICKNESS_DIFF = 0.3;
    private static final int MIN_PIECES_PER_BATCH = 4;
    private static final int MAX_PIECES_PER_BATCH = 5;

    public List<Batch> generateOptimalBatches(List<PieceStep> allPieceSteps) {
        // 按温度分组
        Map<String, List<PieceStep>> pieceStepsByTemp = allPieceSteps.stream()
                .collect(Collectors.groupingBy(PieceStep::getTemperature));

        List<Batch> allBatches = new ArrayList<>();

        // 对每个温度组分别优化
        for (Map.Entry<String, List<PieceStep>> entry : pieceStepsByTemp.entrySet()) {
            String temperature = entry.getKey();
            List<PieceStep> pieceSteps = entry.getValue();

            // 按照厚度对PieceStep进行排序（用于后续处理）
            pieceSteps.sort(Comparator.comparing(PieceStep::getThickness));

            // 用CPLEX求解该温度组的最优分配
            List<Batch> batches = optimizeBatchesForTemperatureGroup(pieceSteps, temperature);
            allBatches.addAll(batches);
        }

        return allBatches;
    }

    private List<Batch> optimizeBatchesForTemperatureGroup(List<PieceStep> pieceSteps, String temperature) {
        // 结果集
        List<Batch> result = new ArrayList<>();

        try {
            // 创建CPLEX实例
            IloCplex cplex = new IloCplex();

            int n = pieceSteps.size(); // PieceStep数量
            int maxBatches = (int) Math.ceil((double) n / MIN_PIECES_PER_BATCH); // 最大可能的Batch数量

            // 决策变量: x[i][j][k] = 1 表示pieceSteps[i]被分配到第j个Batch的第k个位置
            IloIntVar[][][] x = new IloIntVar[n][maxBatches][MAX_PIECES_PER_BATCH];

            // 初始化决策变量
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < maxBatches; j++) {
                    for (int k = 0; k < MAX_PIECES_PER_BATCH; k++) {
                        x[i][j][k] = cplex.boolVar("x_" + i + "_" + j + "_" + k);
                    }
                }
            }

            // 辅助变量: y[j] = 1 表示使用第j个Batch
            IloIntVar[] y = new IloIntVar[maxBatches];
            for (int j = 0; j < maxBatches; j++) {
                y[j] = cplex.boolVar("y_" + j);
            }

            // 约束1: 每个PieceStep只能分配到一个Batch的一个位置
            for (int i = 0; i < n; i++) {
                IloLinearNumExpr expr = cplex.linearNumExpr();
                for (int j = 0; j < maxBatches; j++) {
                    for (int k = 0; k < MAX_PIECES_PER_BATCH; k++) {
                        expr.addTerm(1, x[i][j][k]);
                    }
                }
                cplex.addEq(expr, 1);
            }

            // 约束2: 每个Batch位置至多分配一个PieceStep
            for (int j = 0; j < maxBatches; j++) {
                for (int k = 0; k < MAX_PIECES_PER_BATCH; k++) {
                    IloLinearNumExpr expr = cplex.linearNumExpr();
                    for (int i = 0; i < n; i++) {
                        expr.addTerm(1, x[i][j][k]);
                    }
                    cplex.addLe(expr, 1);
                }
            }

            // 约束3: 每个Batch分配到的PieceStep数量为4或5
            for (int j = 0; j < maxBatches; j++) {
                IloLinearNumExpr expr = cplex.linearNumExpr();
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < MAX_PIECES_PER_BATCH; k++) {
                        expr.addTerm(1, x[i][j][k]);
                    }
                }
                // 如果Batch被使用(y[j]=1)，则必须包含4-5个PieceStep
                cplex.addLe(expr, cplex.prod(MAX_PIECES_PER_BATCH, y[j]));
                cplex.addGe(expr, cplex.prod(MIN_PIECES_PER_BATCH, y[j]));
            }

            // 约束4: Batch高度约束
            for (int j = 0; j < maxBatches; j++) {
                IloLinearNumExpr pieceWidthSum = cplex.linearNumExpr();
                IloLinearNumExpr pieceCount = cplex.linearNumExpr();

                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < MAX_PIECES_PER_BATCH; k++) {
                        pieceWidthSum.addTerm(pieceSteps.get(i).getWidth(), x[i][j][k]);
                        pieceCount.addTerm(1, x[i][j][k]);
                    }
                }

                // 对流板总高度 = (pieceCount - 1) * CONVECTION_PLATE_HEIGHT
                IloNumExpr convectionHeight = cplex.diff(pieceCount, 1);
                convectionHeight = cplex.prod(CONVECTION_PLATE_HEIGHT, convectionHeight);

                // 总高度 = pieceWidthSum + convectionHeight
                IloNumExpr totalHeight = cplex.sum(pieceWidthSum, convectionHeight);

                // 总高度不超过MAX_HEIGHT
                cplex.addLe(totalHeight, MAX_HEIGHT);
            }

            // 约束5: Batch重量约束
            for (int j = 0; j < maxBatches; j++) {
                IloLinearNumExpr pieceWeightSum = cplex.linearNumExpr();
                IloLinearNumExpr pieceCount = cplex.linearNumExpr();

                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < MAX_PIECES_PER_BATCH; k++) {
                        pieceWeightSum.addTerm(pieceSteps.get(i).getWeight(), x[i][j][k]);
                        pieceCount.addTerm(1, x[i][j][k]);
                    }
                }

                // 对流板总重量 = (pieceCount - 1) * CONVECTION_PLATE_WEIGHT
                IloNumExpr convectionWeight = cplex.diff(pieceCount, 1);
                convectionWeight = cplex.prod(CONVECTION_PLATE_WEIGHT, convectionWeight);

                // 总重量 = pieceWeightSum + convectionWeight
                IloNumExpr totalWeight = cplex.sum(pieceWeightSum, convectionWeight);

                // 总重量不超过MAX_WEIGHT
                cplex.addLe(totalWeight, MAX_WEIGHT);
            }

            // 约束6: 每个Batch前两个PieceStep的thickness >= 0.7
            for (int j = 0; j < maxBatches; j++) {
                for (int k = 0; k < 2; k++) { // 只考虑前两个位置
                    for (int i = 0; i < n; i++) {
                        if (pieceSteps.get(i).getThickness() < MIN_THICKNESS_FIRST_TWO) {
                            // 如果thickness < 0.7，则不能放在前两个位置
                            cplex.addEq(x[i][j][k], 0);
                        }
                    }
                }
            }

            // 约束7: 相邻PieceStep的thickness差值 <= 0.3
            for (int j = 0; j < maxBatches; j++) {
                for (int k = 0; k < MAX_PIECES_PER_BATCH - 1; k++) { // 相邻位置
                    for (int i1 = 0; i1 < n; i1++) {
                        for (int i2 = 0; i2 < n; i2++) {
                            double thicknessDiff = Math.abs(pieceSteps.get(i1).getThickness() - pieceSteps.get(i2).getThickness());
                            if (thicknessDiff > MAX_THICKNESS_DIFF) {
                                // 如果thickness差值 > 0.3，则不能放在相邻位置
                                IloNumExpr sum = cplex.sum(x[i1][j][k], x[i2][j][k + 1]);
                                cplex.addLe(sum, 1);
                            }
                        }
                    }
                }
            }

            // 目标函数: 最小化使用的Batch数量
            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int j = 0; j < maxBatches; j++) {
                objective.addTerm(1, y[j]);
            }
            cplex.addMinimize(objective);

            // 求解模型
            if (cplex.solve()) {
                System.out.println("Solution status: " + cplex.getStatus());
                System.out.println("Objective value: " + cplex.getObjValue());

                // 构造结果
                for (int j = 0; j < maxBatches; j++) {
                    if (cplex.getValue(y[j]) > 0.5) { // 批次j被使用
                        List<PieceStep> batchPieces = new ArrayList<>();
                        double batchHeight = 0;
                        double batchWeight = 0;
                        int pieceCount = 0;

                        // 找出该批次的所有PieceStep并按位置排序
                        Map<Integer, PieceStep> positionMap = new HashMap<>();
                        for (int i = 0; i < n; i++) {
                            for (int k = 0; k < MAX_PIECES_PER_BATCH; k++) {
                                if (cplex.getValue(x[i][j][k]) > 0.5) {
                                    positionMap.put(k, pieceSteps.get(i));
                                    pieceCount++;
                                    batchWeight += pieceSteps.get(i).getWeight();
                                    batchHeight += pieceSteps.get(i).getWidth();
                                }
                            }
                        }

                        // 按位置添加到批次
                        for (int k = 0; k < MAX_PIECES_PER_BATCH; k++) {
                            if (positionMap.containsKey(k)) {
                                batchPieces.add(positionMap.get(k));
                            }
                        }

                        // 计算对流板的贡献
                        batchHeight += (pieceCount - 1) * CONVECTION_PLATE_HEIGHT;
                        batchWeight += (pieceCount - 1) * CONVECTION_PLATE_WEIGHT;

                        // 创建Batch
                        Batch batch = Batch.builder()
                                .batchId("Batch_" + j)
                                .pieceSteps(batchPieces)
                                .height(batchHeight)
                                .weight(batchWeight)
                                .build();

                        result.add(batch);
                    }
                }
            } else {
                System.out.println("No solution found.");
            }

            cplex.end();

        } catch (IloException e) {
            System.err.println("CPLEX Error: " + e);
        }

        return result;
    }

    // 启发式算法方法作为CPLEX的替代或补充
    public List<Batch> generateBatchesHeuristic(List<PieceStep> allPieceSteps) {
        // 按温度分组
        Map<String, List<PieceStep>> pieceStepsByTemp = allPieceSteps.stream()
                .collect(Collectors.groupingBy(PieceStep::getTemperature));

        List<Batch> allBatches = new ArrayList<>();

        // 对每个温度组分别优化
        for (Map.Entry<String, List<PieceStep>> entry : pieceStepsByTemp.entrySet()) {
            String temperature = entry.getKey();
            List<PieceStep> pieceSteps = entry.getValue();

            // 将PieceStep按厚度排序
            List<PieceStep> sortedPieces = new ArrayList<>(pieceSteps);
            sortedPieces.sort(Comparator.comparing(PieceStep::getThickness));

            List<Batch> batches = new ArrayList<>();
            List<PieceStep> currentBatch = new ArrayList<>();
            double currentHeight = 0;
            double currentWeight = 0;

            // 厚度>=0.7的PieceStep（用于前两个位置）
            List<PieceStep> thickPieces = sortedPieces.stream()
                    .filter(p -> p.getThickness() >= MIN_THICKNESS_FIRST_TWO)
                    .collect(Collectors.toList());

            // 剩余的PieceStep
            List<PieceStep> remainingPieces = sortedPieces.stream()
                    .filter(p -> p.getThickness() < MIN_THICKNESS_FIRST_TWO)
                    .collect(Collectors.toList());

            // 先处理厚度足够的PieceStep
            int batchCounter = 1;
            while (!thickPieces.isEmpty() || !remainingPieces.isEmpty()) {
                currentBatch = new ArrayList<>();
                currentHeight = 0;
                currentWeight = 0;

                // 先添加两个厚度>=0.7的PieceStep
                for (int i = 0; i < 2; i++) {
                    if (thickPieces.isEmpty()) break;

                    PieceStep piece = thickPieces.remove(0);
                    currentBatch.add(piece);
                    currentHeight += piece.getWidth();
                    currentWeight += piece.getWeight();

                    // 第一个添加后需要考虑对流板
                    if (i == 0) {
                        currentHeight += CONVECTION_PLATE_HEIGHT;
                        currentWeight += CONVECTION_PLATE_WEIGHT;
                    }
                }

                // 如果厚PieceStep不足2个，则无法形成有效的Batch
                if (currentBatch.size() < 2 && thickPieces.isEmpty()) {
                    // 将剩余的厚度不足的PieceStep添加到最后一个Batch中，如果可能的话
                    if (!batches.isEmpty() && !remainingPieces.isEmpty()) {
                        Batch lastBatch = batches.get(batches.size() - 1);

                        // 尝试添加剩余的PieceStep到最后一个Batch
                        while (!remainingPieces.isEmpty() &&
                                lastBatch.getPieceSteps().size() < MAX_PIECES_PER_BATCH) {
                            PieceStep piece = remainingPieces.remove(0);

                            // 检查厚度差值约束
                            PieceStep lastPiece = lastBatch.getPieceSteps().get(lastBatch.getPieceSteps().size() - 1);
                            if (Math.abs(lastPiece.getThickness() - piece.getThickness()) <= MAX_THICKNESS_DIFF) {
                                lastBatch.getPieceSteps().add(piece);
                                lastBatch.setHeight(lastBatch.getHeight() + piece.getWidth() + CONVECTION_PLATE_HEIGHT);
                                lastBatch.setWeight(lastBatch.getWeight() + piece.getWeight() + CONVECTION_PLATE_WEIGHT);
                            }
                        }
                    }

                    // 如果还有剩余的PieceStep，则无法满足约束
                    if (!remainingPieces.isEmpty() || !thickPieces.isEmpty()) {
                        System.out.println("Warning: Some PieceSteps cannot be allocated due to thickness constraints.");
                    }

                    break;
                }

                // 然后添加其他PieceStep，考虑厚度差值约束
                List<PieceStep> availablePieces = new ArrayList<>(remainingPieces);
                availablePieces.addAll(thickPieces);

                // 添加额外的PieceStep，直到达到4-5个
                while (currentBatch.size() < MAX_PIECES_PER_BATCH && !availablePieces.isEmpty()) {
                    PieceStep lastPiece = currentBatch.get(currentBatch.size() - 1);

                    // 找出满足厚度差值约束的PieceStep
                    List<PieceStep> validPieces = availablePieces.stream()
                            .filter(p -> Math.abs(p.getThickness() - lastPiece.getThickness()) <= MAX_THICKNESS_DIFF)
                            .collect(Collectors.toList());

                    if (validPieces.isEmpty()) break;

                    // 选择厚度最接近的PieceStep
                    PieceStep nextPiece = validPieces.stream()
                            .min(Comparator.comparingDouble(p ->
                                    Math.abs(p.getThickness() - lastPiece.getThickness())))
                            .orElse(null);

                    if (nextPiece == null) break;

                    // 检查添加后是否超过高度和重量限制
                    double newHeight = currentHeight + nextPiece.getWidth() + CONVECTION_PLATE_HEIGHT;
                    double newWeight = currentWeight + nextPiece.getWeight() + CONVECTION_PLATE_WEIGHT;

                    if (newHeight <= MAX_HEIGHT && newWeight <= MAX_WEIGHT) {
                        currentBatch.add(nextPiece);
                        currentHeight = newHeight;
                        currentWeight = newWeight;

                        // 从可用列表中移除已使用的PieceStep
                        availablePieces.remove(nextPiece);
                        if (thickPieces.contains(nextPiece)) {
                            thickPieces.remove(nextPiece);
                        } else {
                            remainingPieces.remove(nextPiece);
                        }
                    } else {
                        break;
                    }
                }

                // 检查批次是否满足条件(4-5个PieceStep)
                if (currentBatch.size() >= MIN_PIECES_PER_BATCH && currentBatch.size() <= MAX_PIECES_PER_BATCH) {
                    Batch batch = Batch.builder()
                            .batchId("Batch_" + batchCounter++)
                            .pieceSteps(currentBatch)
                            .height(currentHeight)
                            .weight(currentWeight)
                            .build();

                    batches.add(batch);
                } else {
                    // 如果当前批次不满足条件，将PieceStep放回可用列表
                    for (PieceStep piece : currentBatch) {
                        if (piece.getThickness() >= MIN_THICKNESS_FIRST_TWO) {
                            thickPieces.add(piece);
                        } else {
                            remainingPieces.add(piece);
                        }
                    }

                    // 如果无法形成新的批次，跳出循环
                    if (batches.isEmpty()) {
                        System.out.println("Warning: Cannot form valid batches with given constraints.");
                        break;
                    }
                }
            }

            allBatches.addAll(batches);
        }

        return allBatches;
    }
}
