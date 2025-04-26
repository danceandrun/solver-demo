package com.example.optimization.solverdemo.controller;

import com.example.optimization.solverdemo.service.OptimizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/solve")
public class OptimizationController {

    private final OptimizationService optimizationService;

    @Autowired
    public OptimizationController(OptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    @GetMapping("/cplex")
    public String triggerCplexSolve() {
        return optimizationService.solveWithCplex();
    }

    @GetMapping("/gurobi")
    public String triggerGurobiSolve() {
        return optimizationService.solveWithGurobi();
    }
}