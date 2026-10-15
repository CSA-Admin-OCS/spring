package com.open.spring.mvc.assignments;

import java.util.Map;

import com.open.spring.mvc.assignments.AssignmentSubmissionAPIController.AssignmentReturnDto;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.Submitter;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssignmentSubmissionReturnDto {
    public Long id;
    public AssignmentReturnDto assignment;
    public Submitter submitter;
    public Boolean isGroup;
    public Map<String, Object> content;
    public String comment;
    public Double grade;
    public String feedback;
    public Boolean isLate;
    public String aiSummary;
    public Integer qualityScore;

    public AssignmentSubmissionReturnDto(AssignmentSubmission submission) {
        this.id = submission.getId();
        this.assignment = new AssignmentReturnDto(submission.getAssignment());
        this.isGroup = submission.getSubmitter() instanceof Groups;
        this.submitter = submission.getSubmitter();
        this.content = submission.getContent();
        this.comment = submission.getComment();
        this.grade = submission.getGrade();
        this.feedback = submission.getFeedback();
        this.isLate = submission.getIsLate();
        this.aiSummary = submission.getAiSummary();
        this.qualityScore = submission.getQualityScore();
    }
}