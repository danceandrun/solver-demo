package com.example.optimization.solverdemo.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PieceStep {
    private String pieceStepId;
    private Double weight;
    private Double width;
    private Double thickness;
    private String temperature;
    private Double outerDiameter;//外径
}
