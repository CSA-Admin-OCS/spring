package com.open.spring.mvc.questboard;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor

public class Questboard {
    private Long id;
    private String title;
    private String description;
    private String difficulty;
    private int xp;
    private String status;
    private String deadline;
}