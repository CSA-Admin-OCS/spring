package com.open.spring.mvc.toolchain;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Teacher/admin page: every student's score and progress for each CS Pathway level.
 * Access is restricted in MvcSecurityConfig.
 */
@Controller
@RequestMapping("/mvc/cs-pathway")
public class ToolchainMvcController {
    private final ToolchainScoresService scoreService;
    private final ToolchainReportService setupReportService;
    private final ToolchainHelpService helpRequestService;

    public ToolchainMvcController(ToolchainScoresService scoreService, ToolchainReportService setupReportService,
            ToolchainHelpService helpRequestService) {
        this.scoreService = scoreService;
        this.setupReportService = setupReportService;
        this.helpRequestService = helpRequestService;
    }

    @GetMapping("/read")
    public String read(Model model) {
        model.addAttribute("levels", ToolchainEnumLevels.values());
        model.addAttribute("rows", scoreService.getStudentRows());
        model.addAttribute("setupReports", setupReportService.getReportsByUid());
        model.addAttribute("helpRequests", helpRequestService.getRecentRequests());
        return "cs-pathway/read";
    }
}
