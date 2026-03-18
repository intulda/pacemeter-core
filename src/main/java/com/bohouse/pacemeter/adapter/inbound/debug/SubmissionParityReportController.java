package com.bohouse.pacemeter.adapter.inbound.debug;

import com.bohouse.pacemeter.application.SubmissionParityReport;
import com.bohouse.pacemeter.application.SubmissionParityQualityService;
import com.bohouse.pacemeter.application.SubmissionParityReportService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/debug/parity")
public class SubmissionParityReportController {

    private final SubmissionParityReportService submissionParityReportService;
    private final SubmissionParityQualityService submissionParityQualityService;

    public SubmissionParityReportController(
            SubmissionParityReportService submissionParityReportService,
            SubmissionParityQualityService submissionParityQualityService
    ) {
        this.submissionParityReportService = submissionParityReportService;
        this.submissionParityQualityService = submissionParityQualityService;
    }

    @GetMapping("/submissions/{submissionId}")
    public SubmissionParityReport submissionReport(@PathVariable String submissionId) throws IOException {
        return submissionParityReportService.buildReport(submissionId);
    }

    @GetMapping("/quality")
    public SubmissionParityQualityService.SubmissionParityQualityRollup qualityRollup() throws IOException {
        return submissionParityQualityService.buildRollup();
    }
}
