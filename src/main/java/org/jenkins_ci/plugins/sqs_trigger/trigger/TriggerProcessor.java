package org.jenkins_ci.plugins.sqs_trigger.trigger;

import hudson.model.AbstractProject;
import hudson.triggers.Trigger;

import java.util.List;

/**
 * Processes an payload to determine what jobs to trigger
 *
 * @author aaronwalker
 */
public interface TriggerProcessor {

    void trigger(String payload);
}
