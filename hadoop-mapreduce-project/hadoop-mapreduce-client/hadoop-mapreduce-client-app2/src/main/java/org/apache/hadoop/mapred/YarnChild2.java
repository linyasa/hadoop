/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.mapred;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.filecache.DistributedCache;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.mapreduce.security.token.JobTokenIdentifier;
import org.apache.hadoop.mapreduce.security.token.JobTokenSecretManager;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.metrics2.source.JvmMetrics;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.DiskChecker.DiskErrorException;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.log4j.LogManager;

/**
 * The main() for MapReduce task processes.
 */
class YarnChild2 {

  private static final Log LOG = LogFactory.getLog(YarnChild2.class);

  static volatile TaskAttemptID taskid = null;

  public static void main(String[] args) throws Throwable {
    LOG.info("XXX: Child starting");

    final JobConf defaultConf = new JobConf();
    defaultConf.addResource(MRJobConfig.JOB_CONF_FILE);
    UserGroupInformation.setConfiguration(defaultConf);

    String host = args[0];
    int port = Integer.parseInt(args[1]);
    final InetSocketAddress address =
        NetUtils.createSocketAddrForHost(host, port);
    final TaskAttemptID firstTaskid = TaskAttemptID.forName(args[2]);
    int jvmIdInt = Integer.parseInt(args[3]);
    JVMId jvmId = new JVMId(firstTaskid.getJobID(),
        firstTaskid.getTaskType() == TaskType.MAP, jvmIdInt);

    // initialize metrics
    DefaultMetricsSystem.initialize(
        StringUtils.camelize(firstTaskid.getTaskType().name()) +"Task");

    Token<JobTokenIdentifier> jt = loadCredentials(defaultConf, address);

    // Create TaskUmbilicalProtocol as actual task owner.
    UserGroupInformation taskOwner =
      UserGroupInformation.createRemoteUser(firstTaskid.getJobID().toString());
    taskOwner.addToken(jt);
    final TaskUmbilicalProtocol umbilical =
      taskOwner.doAs(new PrivilegedExceptionAction<TaskUmbilicalProtocol>() {
      @Override
      public TaskUmbilicalProtocol run() throws Exception {
        return (TaskUmbilicalProtocol)RPC.getProxy(TaskUmbilicalProtocol.class,
            TaskUmbilicalProtocol.versionID, address, defaultConf);
      }
    });

    // report non-pid to application master
    JvmContext context = new JvmContext(jvmId, "-1000");
    LOG.debug("PID: " + System.getenv().get("JVM_PID"));
    Task task = null;
    UserGroupInformation childUGI = null;

    try {
      while (true) {
        LOG.info("Polling for next task");
      int idleLoopCount = 0;
      JvmTask myTask = null;;
      // poll for new task
      for (int idle = 0; null == myTask; ++idle) {
//        long sleepTimeMilliSecs = Math.min(idle * 500, 1500);
        // XXX: Figure out sleep time.
        long sleepTimeMilliSecs = 20;
        LOG.info("Sleeping for " + sleepTimeMilliSecs
            + "ms before retrying again. Got null now.");
        MILLISECONDS.sleep(sleepTimeMilliSecs);
        myTask = umbilical.getTask(context);
      }
      if (myTask.shouldDie()) {
        return;
      }

      task = myTask.getTask();
      YarnChild2.taskid = task.getTaskID();

      // Create the job-conf and set credentials
      final JobConf job =
        configureTask(task, defaultConf.getCredentials(), jt);

      // Initiate Java VM metrics
      JvmMetrics.initSingleton(jvmId.toString(), job.getSessionId());
      childUGI = UserGroupInformation.createRemoteUser(System
          .getenv(ApplicationConstants.Environment.USER.toString()));
      // Add tokens to new user so that it may execute its task correctly.
      for(Token<?> token : UserGroupInformation.getCurrentUser().getTokens()) {
        childUGI.addToken(token);
      }

      // Create a final reference to the task for the doAs block
      final Task taskFinal = task;
      childUGI.doAs(new PrivilegedExceptionAction<Object>() {
        @Override
        public Object run() throws Exception {
          // use job-specified working directory
          FileSystem.get(job).setWorkingDirectory(job.getWorkingDirectory());
          taskFinal.run(job, umbilical); // run the task
          return null;
        }
      });
      LOG.info("XXX: _______Done executing one task_________");
      }
    } catch (FSError e) {
      LOG.fatal("FSError from child", e);
      umbilical.fsError(taskid, e.getMessage());
    } catch (Exception exception) {
      LOG.warn("Exception running child : "
          + StringUtils.stringifyException(exception));
      try {
        if (task != null) {
          // do cleanup for the task
          if (childUGI == null) { // no need to job into doAs block
            task.taskCleanup(umbilical);
          } else {
            final Task taskFinal = task;
            childUGI.doAs(new PrivilegedExceptionAction<Object>() {
              @Override
              public Object run() throws Exception {
                taskFinal.taskCleanup(umbilical);
                return null;
              }
            });
          }
        }
      } catch (Exception e) {
        LOG.info("Exception cleaning up: " + StringUtils.stringifyException(e));
      }
      // Report back any failures, for diagnostic purposes
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      exception.printStackTrace(new PrintStream(baos));
      if (taskid != null) {
        umbilical.fatalError(taskid, baos.toString());
      }
    } catch (Throwable throwable) {
      LOG.fatal("Error running child : "
    	        + StringUtils.stringifyException(throwable));
      if (taskid != null) {
        Throwable tCause = throwable.getCause();
        String cause = tCause == null
                                 ? throwable.getMessage()
                                 : StringUtils.stringifyException(tCause);
        umbilical.fatalError(taskid, cause);
      }
    } finally {
      RPC.stopProxy(umbilical);
      DefaultMetricsSystem.shutdown();
      // Shutting down log4j of the child-vm...
      // This assumes that on return from Task.run()
      // there is no more logging done.
      LogManager.shutdown();
    }
  }

  private static Token<JobTokenIdentifier> loadCredentials(JobConf conf,
      InetSocketAddress address) throws IOException {
    //load token cache storage
    String tokenFileLocation =
        System.getenv(ApplicationConstants.CONTAINER_TOKEN_FILE_ENV_NAME);
    String jobTokenFile =
        new Path(tokenFileLocation).makeQualified(FileSystem.getLocal(conf))
            .toUri().getPath();
    Credentials credentials =
      TokenCache.loadTokens(jobTokenFile, conf);
    LOG.debug("loading token. # keys =" +credentials.numberOfSecretKeys() +
        "; from file=" + jobTokenFile);
    Token<JobTokenIdentifier> jt = TokenCache.getJobToken(credentials);
    SecurityUtil.setTokenService(jt, address);
    UserGroupInformation current = UserGroupInformation.getCurrentUser();
    current.addToken(jt);
    for (Token<? extends TokenIdentifier> tok : credentials.getAllTokens()) {
      current.addToken(tok);
    }
    // Set the credentials
    conf.setCredentials(credentials);
    return jt;
  }

  /**
   * Configure mapred-local dirs. This config is used by the task for finding
   * out an output directory.
   * @throws IOException 
   */
  private static void configureLocalDirs(Task task, JobConf job) throws IOException {
    String[] localSysDirs = StringUtils.getTrimmedStrings(
        System.getenv(ApplicationConstants.LOCAL_DIR_ENV));
    job.setStrings(MRConfig.LOCAL_DIR, localSysDirs);
    LOG.info(MRConfig.LOCAL_DIR + " for child: " + job.get(MRConfig.LOCAL_DIR));
    LocalDirAllocator lDirAlloc = new LocalDirAllocator(MRConfig.LOCAL_DIR);
    Path workDir = null;
    // First, try to find the JOB_LOCAL_DIR on this host.
    try {
      workDir = lDirAlloc.getLocalPathToRead("work", job);
    } catch (DiskErrorException e) {
      // DiskErrorException means dir not found. If not found, it will
      // be created below.
    }
    if (workDir == null) {
      // JOB_LOCAL_DIR doesn't exist on this host -- Create it.
      workDir = lDirAlloc.getLocalPathForWrite("work", job);
      FileSystem lfs = FileSystem.getLocal(job).getRaw();
      boolean madeDir = false;
      try {
        madeDir = lfs.mkdirs(workDir);
      } catch (FileAlreadyExistsException e) {
        // Since all tasks will be running in their own JVM, the race condition
        // exists where multiple tasks could be trying to create this directory
        // at the same time. If this task loses the race, it's okay because
        // the directory already exists.
        madeDir = true;
        workDir = lDirAlloc.getLocalPathToRead("work", job);
      }
      if (!madeDir) {
          throw new IOException("Mkdirs failed to create "
              + workDir.toString());
      }
    }
    job.set(MRJobConfig.JOB_LOCAL_DIR,workDir.toString());
  }

  private static JobConf configureTask(Task task, Credentials credentials,
      Token<JobTokenIdentifier> jt) throws IOException {
    final JobConf job = new JobConf(MRJobConfig.JOB_CONF_FILE);
    job.setCredentials(credentials);
    
    String appAttemptIdEnv = System
        .getenv(MRJobConfig.APPLICATION_ATTEMPT_ID_ENV);
    LOG.debug("APPLICATION_ATTEMPT_ID: " + appAttemptIdEnv);
    // Set it in conf, so as to be able to be used the the OutputCommitter.
    job.setInt(MRJobConfig.APPLICATION_ATTEMPT_ID, Integer
        .parseInt(appAttemptIdEnv));

    // set tcp nodelay
    job.setBoolean("ipc.client.tcpnodelay", true);
    job.setClass(MRConfig.TASK_LOCAL_OUTPUT_CLASS,
        YarnOutputFiles.class, MapOutputFile.class);
    // set the jobTokenFile into task
    task.setJobTokenSecret(
        JobTokenSecretManager.createSecretKey(jt.getPassword()));

    // setup the child's MRConfig.LOCAL_DIR.
    configureLocalDirs(task, job);

    // setup the child's attempt directories
    // Do the task-type specific localization
    task.localizeConfiguration(job);

    // Set up the DistributedCache related configs
    setupDistributedCacheConfig(job);

    // Overwrite the localized task jobconf which is linked to in the current
    // work-dir.
    Path localTaskFile = new Path(MRJobConfig.JOB_CONF_FILE);
    writeLocalJobFile(localTaskFile, job);
    task.setJobFile(localTaskFile.toString());
    task.setConf(job);
    return job;
  }

  /**
   * Set up the DistributedCache related configs to make
   * {@link DistributedCache#getLocalCacheFiles(Configuration)}
   * and
   * {@link DistributedCache#getLocalCacheArchives(Configuration)}
   * working.
   * @param job
   * @throws IOException
   */
  private static void setupDistributedCacheConfig(final JobConf job)
      throws IOException {

    String localWorkDir = System.getenv("PWD");
    //        ^ ^ all symlinks are created in the current work-dir

    // Update the configuration object with localized archives.
    URI[] cacheArchives = DistributedCache.getCacheArchives(job);
    if (cacheArchives != null) {
      List<String> localArchives = new ArrayList<String>();
      for (int i = 0; i < cacheArchives.length; ++i) {
        URI u = cacheArchives[i];
        Path p = new Path(u);
        Path name =
            new Path((null == u.getFragment()) ? p.getName()
                : u.getFragment());
        String linkName = name.toUri().getPath();
        localArchives.add(new Path(localWorkDir, linkName).toUri().getPath());
      }
      if (!localArchives.isEmpty()) {
        job.set(MRJobConfig.CACHE_LOCALARCHIVES, StringUtils
            .arrayToString(localArchives.toArray(new String[localArchives
                .size()])));
      }
    }

    // Update the configuration object with localized files.
    URI[] cacheFiles = DistributedCache.getCacheFiles(job);
    if (cacheFiles != null) {
      List<String> localFiles = new ArrayList<String>();
      for (int i = 0; i < cacheFiles.length; ++i) {
        URI u = cacheFiles[i];
        Path p = new Path(u);
        Path name =
            new Path((null == u.getFragment()) ? p.getName()
                : u.getFragment());
        String linkName = name.toUri().getPath();
        localFiles.add(new Path(localWorkDir, linkName).toUri().getPath());
      }
      if (!localFiles.isEmpty()) {
        job.set(MRJobConfig.CACHE_LOCALFILES,
            StringUtils.arrayToString(localFiles
                .toArray(new String[localFiles.size()])));
      }
    }
  }

  private static final FsPermission urw_gr =
    FsPermission.createImmutable((short) 0640);

  /**
   * Write the task specific job-configuration file.
   * @throws IOException
   */
  private static void writeLocalJobFile(Path jobFile, JobConf conf)
      throws IOException {
    FileSystem localFs = FileSystem.getLocal(conf);
    localFs.delete(jobFile);
    OutputStream out = null;
    try {
      out = FileSystem.create(localFs, jobFile, urw_gr);
      conf.writeXml(out);
    } finally {
      IOUtils.cleanup(LOG, out);
    }
  }

}
