package com.acme.report;

import org.springframework.stereotype.Component;

@Component
public class ReportLinkRenderer {

    public String renderDownloadLink(String region, String bucket, String objectKey) {
        String reference = region + ":" + bucket + ":" + objectKey;
        return "/api/assets/" + reference + "/download";
    }
}
