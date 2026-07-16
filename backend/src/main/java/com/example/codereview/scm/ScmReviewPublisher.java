package com.example.codereview.scm;

public interface ScmReviewPublisher {

    ScmProviderType type();

    ScmPublicationResult publish(ScmPublicationContext context, ReviewPublication publication);
}
