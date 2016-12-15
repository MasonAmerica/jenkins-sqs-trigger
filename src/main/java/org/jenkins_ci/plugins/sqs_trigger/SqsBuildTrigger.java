package org.jenkins_ci.plugins.sqs_trigger;

import hudson.Extension;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.model.*;
import hudson.scm.SCM;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.SequentialExecutionQueue;
import hudson.util.StreamTaskListener;
import net.sf.json.JSONObject;
import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.plugins.multiplescms.MultiSCM;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Triggers a build when we receive a message from SQS.
 *
 * @author aaronwalker
 * @author zifnab06
 */
public class SqsBuildTrigger extends Trigger<AbstractProject> implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(SqsBuildTrigger.class.getName());

    @DataBoundConstructor
    public SqsBuildTrigger() {
    }

    public void onPost() {
        getDescriptor().queue.execute(this);
    }

    public void onPost(String username) {
        onPost();
    }

    /**
     * Returns the file that records the last/current polling activity.
     */
    public File getLogFile() {
        return new File(job.getRootDir(),"sqs-polling.log");
    }

    public void run() {
        try {
            StreamTaskListener listener = new StreamTaskListener(getLogFile());

            try {
                PrintStream logger = listener.getLogger();
                long start = System.currentTimeMillis();
                logger.println("Started on "+ DateFormat.getDateTimeInstance().format(new Date()));
                boolean result = job.poll(listener).hasChanges();
                logger.println("Done. Took "+ Util.getTimeSpanString(System.currentTimeMillis() - start));
                if(result) {
                    logger.println("Changes found");
                    // Fix for JENKINS-16617, JENKINS-16669
                    // The Cause instance needs to have a unique identity (when equals() is called), otherwise
                    // scheduleBuild() returns false - indicating that this job is already in the queue or
                    // has already been processed.
                    if (job.scheduleBuild(new Cause.RemoteCause("SQS", "SQS poll initiated on " +
                            DateFormat.getDateTimeInstance().format(new Date(start))))) {
                      logger.println("Job queued");
                    }
                    else {
                      logger.println("Job NOT queued - it was determined that this job has been queued already.");
                    }
                } else {
                    logger.println("No changes");
                }
            } finally {
                listener.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,"Failed to record SCM polling",e);
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        return Collections.singleton(new SqsBuildTriggerPollingAction());
    }

    public final class SqsBuildTriggerPollingAction implements Action {

        public AbstractProject<?,?> getOwner() {
            return job;
        }

        public String getIconFileName() {
            return "clipboard.png";
        }

        public String getDisplayName() {
            return "SQS Activity Log";
        }

        public String getUrlName() {
            return "SQSActivityLog";
        }

        public String getLog() throws IOException {
            return Util.loadFile(getLogFile());
        }

        public void writeLogTo(XMLOutput out) throws IOException {
            new AnnotatedLargeText<SqsBuildTriggerPollingAction>(getLogFile(), Charset.defaultCharset(),true,this).writeHtmlTo(0,out.asWriter());
        }
    }

    @Extension
    public static class DescriptorImpl extends TriggerDescriptor {
        private transient final SequentialExecutionQueue queue = new SequentialExecutionQueue(Executors.newSingleThreadExecutor());

        private boolean manageHook = true;
        private volatile List<SqsProfile> sqsProfiles = new ArrayList<SqsProfile>();

        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean isApplicable(Item item) {
            return item instanceof AbstractProject;
        }

        @Override
        public String getDisplayName() {
            return "Build when a message is published to an SQS Queue";
        }

        public boolean isManageHook() {
            return manageHook;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            JSONObject sqs = json.getJSONObject("sqsProfiles");
            sqsProfiles = req.bindJSONToList(SqsProfile.class,sqs);
            save();
            return true;
        }

        public static DescriptorImpl get() {
            return Trigger.all().get(DescriptorImpl.class);
        }

        public List<SqsProfile> getSqsProfiles() {
            return sqsProfiles;
        }
    }
}
