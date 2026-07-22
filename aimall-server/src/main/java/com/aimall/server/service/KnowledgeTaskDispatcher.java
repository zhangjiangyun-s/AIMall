package com.aimall.server.service;

public interface KnowledgeTaskDispatcher {

    void dispatchAfterCommit(String taskId);

    void dispatch(String taskId);

    int dispatchPending(int limit);
}
