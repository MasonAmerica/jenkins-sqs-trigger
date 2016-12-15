package org.jenkins_ci.plugins.sqs_trigger.trigger;

import org.jenkins_ci.plugins.sqs_trigger.SqsBuildTrigger;
import org.jenkins_ci.plugins.sqs_trigger.trigger.TriggerProcessor;
import hudson.model.AbstractProject;
import hudson.model.BooleanParameterValue;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes a github commit hook payload
 *
 * @author aaronwalker
 * @author zifnab06
 */
public class JenkinsTriggerProcessor implements TriggerProcessor {

    private static final Pattern REPOSITORY_NAME_PATTERN = Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+)");
    private static final Logger LOGGER = Logger.getLogger(JenkinsTriggerProcessor.class.getName());

    public void trigger(String payload) {
        JSONObject json = extractJsonFromPayload(payload);
        // Format: job: "name", parameters: {}
        if (!json.has("job")){
          //return exception
        }
        String job = json.getString("job");
        List parameters = getParamsFromJson(json);
        //Launch Job
        processPayload(json, SqsBuildTrigger.class);

    }
    public void processPayload(JSONObject json, Class<? extends Trigger> triggerClass) {
        // Note that custom payloads will only trigger jobs that are configured with this SQS trigger.
        // The custom payload must contain a root object named "job" of type string.
        String jobToTrigger = json.getString("job");
        if (jobToTrigger == null) {
            LOGGER.warning("Sqs message payload does not contain information about which job to trigger.");
            return;
        }
        // The custom payload can contain a root object named "parameters"
        // that contains a list of parameters to pass to the job when scheduled.
        // Each parameter object should contain information about its type, name, and value.
        // TODO this will only work with string or boolean parameters,
        // and you need to pass in ALL parameters for the job,
        // as the scheduled job will not fill in any defaults for you.
        List<ParameterValue> parameters = getParamsFromJson(json);

        Authentication old = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(ACL.SYSTEM);
        try {
            Jenkins jenkins = Jenkins.getInstance();
            for (Job job: jenkins.getAllItems(Job.class)) {
                String jobName = job.getDisplayName();
                if (jobName.equals(jobToTrigger)) {
                    // Custom triggers operate on Parameterized jobs only
                    if (job instanceof ParameterizedJobMixIn.ParameterizedJob) {
                        // Make sure the job is configured to use the SQS trigger
                        ParameterizedJobMixIn.ParameterizedJob pJob = (ParameterizedJobMixIn.ParameterizedJob) job;
                        final Map<TriggerDescriptor, Trigger<?>> pJobTriggers = pJob.getTriggers();
                        SqsBuildTrigger.DescriptorImpl descriptor = jenkins.getDescriptorByType(SqsBuildTrigger.DescriptorImpl.class);
                        if (!pJobTriggers.containsKey(descriptor)) {
                            LOGGER.warning("The job " + jobName + " is not configured to use the SQS trigger.");
                        } else {
                            final Job theJob = job;
                            ParameterizedJobMixIn mixin = new ParameterizedJobMixIn() {
                                @Override
                                protected Job asJob() {
                                    return theJob;
                                }
                            };
                            Cause cause = new Cause.RemoteCause("SQS", "Triggered by SQS.");
                            CauseAction cAction = new CauseAction(cause);
                            ParametersAction pAction = new ParametersAction(parameters);
                            final QueueTaskFuture queueTaskFuture = mixin.scheduleBuild2(0, cAction, pAction);
                            if (queueTaskFuture == null) {
                                LOGGER.warning("Unable to schedule the job " + jobName);
                            }
                        }
                    } else {
                        LOGGER.warning("The job " + jobName + " is not configured as a Parameterized Job.");
                    }
                }
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(old);
        }
    }

    public JSONObject extractJsonFromPayload(String payload) {
        JSONObject json = JSONObject.fromObject(payload);
        if(json.has("Type")) {
            String msg = json.getString("Message");
            if(msg != null) {
                char ch[] = msg.toCharArray();
                if((ch[0] == '"') && (ch[msg.length()-1]) == '"') {
                    msg = msg.substring(1,msg.length()-1); //remove the leading and trailing double quotes
                }
                return JSONObject.fromObject(msg);
            }
        }
        return json;
    }

    private Boolean isValidParameterJson(JSONObject param) {
        if (param.has("name") && param.has("value") && param.has("type")) {
            if (param.getString("type").matches("string|boolean")) {
                return true;
            } else {
                LOGGER.warning("'string' and 'boolean' are the only supported parameter types.");
                return false;
            }
        } else {
            LOGGER.warning("Parameters must contain key/value pairs for 'name', 'value', and 'type'.");
            return false;
        }
    }

    public List<ParameterValue> getParamsFromJson(JSONObject json) {
        List<ParameterValue> params = new ArrayList<ParameterValue>();
        if (json.has("parameters")) {
            try {
                JSONArray parameters = json.getJSONArray("parameters");
                for (int i = 0; i < parameters.size(); i++) {
                    JSONObject param = (JSONObject) parameters.get(i);
                    if (isValidParameterJson(param)) {
                        String name = param.getString("name");
                        String type = param.getString("type");
                        if (type.equals("boolean")) {
                            Boolean value = param.getBoolean("value");
                            BooleanParameterValue parameterValue = new BooleanParameterValue(name, value);
                            params.add(parameterValue);
                        } else {
                            String value = param.getString("value");
                            StringParameterValue parameterValue = new StringParameterValue(name, value);
                            params.add(parameterValue);
                        }
                    }
                }
            } catch (JSONException e) {
                LOGGER.warning("Parameters must be passed as a JSONArray in the SQS message.");
            }
        }
        return params;
    }
}
