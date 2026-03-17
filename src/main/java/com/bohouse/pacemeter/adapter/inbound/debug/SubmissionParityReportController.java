package com.bohouse.pacemeter.adapter.inbound.debug;

import com.bohouse.pacemeter.application.SubmissionParityReport;
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

    public SubmissionParityReportController(SubmissionParityReportService submissionParityReportService) {
        this.submissionParityReportService = submissionParityReportService;
    }

    @GetMapping("/submissions/{submissionId}")
    public SubmissionParityReport submissionReport(@PathVariable String submissionId) throws IOException {
        return submissionParityReportService.buildReport(submissionId);
    }
}
