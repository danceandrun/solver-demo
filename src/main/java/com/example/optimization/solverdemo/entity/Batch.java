package com.example.optimization.solverdemo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Batch {
    private String batchId;
    private List<PieceStep> pieceSteps;
    private Double height;
    private Double weight;
}
