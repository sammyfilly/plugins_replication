// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.googlesource.gerrit.plugins.replication.PushResultProcessing.NO_OP;
import static java.util.stream.Collectors.toList;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.common.ProjectInfo;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Test;

@UseLocalDisk
@TestPlugin(
    name = "replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.ReplicationModule")
public class ReplicationIT extends LightweightPluginDaemonTest {
  private static final Optional<String> ALL_PROJECTS = Optional.empty();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int TEST_REPLICATION_DELAY = 1;
  private static final int TEST_REPLICATION_RETRY = 1;
  private static final int TEST_PROJECT_CREATION_SECONDS = 10;
  private static final Duration TEST_TIMEOUT =
      Duration.ofSeconds((TEST_REPLICATION_DELAY + TEST_REPLICATION_RETRY * 60) + 1);

  private static final Duration TEST_NEW_PROJECT_TIMEOUT =
      Duration.ofSeconds(
          (TEST_REPLICATION_DELAY + TEST_REPLICATION_RETRY * 60) + TEST_PROJECT_CREATION_SECONDS);

  @Inject private SitePaths sitePaths;
  @Inject private DynamicSet<ProjectDeletedListener> deletedListeners;
  private Path gitPath;
  private FileBasedConfig config;

  @Override
  public void setUpTestPlugin() throws Exception {
    gitPath = sitePaths.site_path.resolve("git");
    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    config.save();
    setReplicationDestination(
        "remote1",
        "suffix1",
        Optional.of("not-used-project")); // Simulates a full replication.config initialization
    super.setUpTestPlugin();
  }

  @Test
  public void shouldReplicateNewProject() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey sourceProject = createTestProject("foo");

    WaitUtil.waitUntil(
        () -> nonEmptyProjectExists(new Project.NameKey(sourceProject + "replica.git")),
        TEST_NEW_PROJECT_TIMEOUT);

    ProjectInfo replicaProject = gApi.projects().name(sourceProject + "replica").get();
    assertThat(replicaProject).isNotNull();
  }

  @Test
  public void shouldReplicateProjectDeletion() throws Exception {
    String projectNameDeleted = "project-deleted";
    Project.NameKey replicaProject = createTestProject(projectNameDeleted + "replica");
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    setProjectDeletionReplication("foo", true);
    reloadConfig();

    ProjectDeletedListener.Event event =
        new ProjectDeletedListener.Event() {
          @Override
          public String getProjectName() {
            return name(projectNameDeleted);
          }

          @Override
          public NotifyHandling getNotify() {
            return NotifyHandling.NONE;
          }
        };

    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(event);
    }

    waitUntil(() -> !nonEmptyProjectExists(replicaProject));
  }

  @Test
  public void shouldReplicateNewChangeRef() throws Exception {
    Project.NameKey targetProject = createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldReplicateNewBranch() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject("projectreplica");
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);

    try (Repository repo = repoManager.openRepository(targetProject);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref masterRef = getRef(sourceRepo, master);
      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(masterRef.getObjectId());
    }
  }

  @Test
  public void shouldReplicateNewBranchToTwoRemotes() throws Exception {
    Project.NameKey targetProject1 = createTestProject("projectreplica1");
    Project.NameKey targetProject2 = createTestProject("projectreplica2");

    setReplicationDestination("foo1", "replica1", ALL_PROJECTS);
    setReplicationDestination("foo2", "replica2", ALL_PROJECTS);
    reloadConfig();

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    try (Repository repo1 = repoManager.openRepository(targetProject1);
        Repository repo2 = repoManager.openRepository(targetProject2)) {
      waitUntil(
          () ->
              (checkedGetRef(repo1, sourceRef) != null && checkedGetRef(repo2, sourceRef) != null));

      Ref targetBranchRef1 = getRef(repo1, sourceRef);
      assertThat(targetBranchRef1).isNotNull();
      assertThat(targetBranchRef1.getObjectId()).isEqualTo(sourceCommit.getId());

      Ref targetBranchRef2 = getRef(repo2, sourceRef);
      assertThat(targetBranchRef2).isNotNull();
      assertThat(targetBranchRef2.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldMatchTemplatedURL() throws Exception {
    Project.NameKey targetProject = createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    String newRef = "refs/heads/newForTest";
    ObjectId newRefTip = createNewBranchWithoutPush("refs/heads/master", newRef);

    String urlMatch = gitPath.resolve("${name}" + "replica" + ".git").toString();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), true);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, newRef) != null);

      Ref targetBranchRef = getRef(repo, newRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(newRefTip);
    }
  }

  @Test
  public void shouldMatchRealURL() throws Exception {
    Project.NameKey targetProject = createTestProject("projectreplica");

    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    String newRef = "refs/heads/newForTest";
    ObjectId newRefTip = createNewBranchWithoutPush("refs/heads/master", newRef);

    String urlMatch = gitPath.resolve(project + "replica" + ".git").toString();

    plugin
        .getSysInjector()
        .getInstance(ReplicationQueue.class)
        .scheduleFullSync(project, urlMatch, new ReplicationState(NO_OP), true);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, newRef) != null);

      Ref targetBranchRef = getRef(repo, newRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(newRefTip);
    }
  }

  @Test
  public void shouldReplicateHeadUpdate() throws Exception {
    setReplicationDestination("foo", "replica", ALL_PROJECTS);
    reloadConfig();

    Project.NameKey targetProject = createTestProject("projectreplica");
    String newHead = "refs/heads/newhead";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newHead).create(input);
    gApi.projects().name(project.get()).head(newHead);

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, newHead) != null);

      Ref targetProjectHead = getRef(repo, Constants.HEAD);
      assertThat(targetProjectHead).isNotNull();
      assertThat(targetProjectHead.getTarget().getName()).isEqualTo(newHead);
    }
  }

  @Test
  public void shouldNotDrainTheQueueWhenReloading() throws Exception {
    // Setup repo to replicate
    Project.NameKey targetProject = createTestProject("projectreplica");
    String remoteName = "doNotDrainQueue";
    setReplicationDestination(remoteName, "replica", ALL_PROJECTS);

    Result pushResult = createChange();
    shutdownConfig();

    pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    assertThrows(
        InterruptedException.class,
        () -> {
          try (Repository repo = repoManager.openRepository(targetProject)) {
            waitUntil(() -> checkedGetRef(repo, sourceRef) != null);
          }
        });
  }

  @Test
  public void shouldDrainTheQueueWhenReloading() throws Exception {
    // Setup repo to replicate
    Project.NameKey targetProject = createTestProject("projectreplica");
    String remoteName = "drainQueue";
    setReplicationDestination(remoteName, "replica", ALL_PROJECTS);

    config.setInt("remote", remoteName, "drainQueueAttempts", 2);
    config.save();
    reloadConfig();

    Result pushResult = createChange();
    shutdownConfig();

    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().getRefName();

    try (Repository repo = repoManager.openRepository(targetProject)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);
      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  private Project.NameKey createTestProject(String name) throws Exception {
    return createProject(name);
  }

  public boolean isPushCompleted(Project.NameKey project, String ref, Duration timeOut) {
    try (Repository repo = repoManager.openRepository(project)) {
      WaitUtil.waitUntil(() -> checkedGetRef(repo, ref) != null, timeOut);
      return true;
    } catch (InterruptedException e) {
      return false;
    } catch (Exception e) {
      throw new RuntimeException("Cannot open repo for project" + project, e);
    }
  }

  private Ref getRef(Repository repo, String branchName) throws IOException {
    return repo.getRefDatabase().exactRef(branchName);
  }

  private Ref checkedGetRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().exactRef(branchName);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in repo %s", branchName, repo);
      return null;
    }
  }

  private void setReplicationDestination(
      String remoteName, String replicaSuffix, Optional<String> project) throws IOException {
    setReplicationDestination(
        remoteName, Arrays.asList(replicaSuffix), project, TEST_REPLICATION_DELAY);
  }

  private FileBasedConfig setReplicationDestination(
      String remoteName,
      List<String> replicaSuffixes,
      Optional<String> project,
      int replicationDelay)
      throws IOException {

    List<String> replicaUrls =
        replicaSuffixes.stream()
            .map(suffix -> gitPath.resolve("${name}" + suffix + ".git").toString())
            .collect(toList());
    config.setStringList("remote", remoteName, "url", replicaUrls);
    config.setInt("remote", remoteName, "replicationDelay", replicationDelay);
    config.setInt("remote", remoteName, "replicationRetry", TEST_REPLICATION_RETRY);
    project.ifPresent(prj -> config.setString("remote", remoteName, "projects", prj));
    config.save();
    return config;
  }

  private void setProjectDeletionReplication(String remoteName, boolean replicateProjectDeletion)
      throws IOException {
    config.setBoolean("remote", remoteName, "replicateProjectDeletions", replicateProjectDeletion);
    config.save();
  }

  private void waitUntil(Supplier<Boolean> waitCondition) throws InterruptedException {
    WaitUtil.waitUntil(waitCondition, TEST_TIMEOUT);
  }

  private void reloadConfig() {
    plugin.getSysInjector().getInstance(AutoReloadConfigDecorator.class).forceReload();
  }

  private void shutdownConfig() {
    plugin.getSysInjector().getInstance(AutoReloadConfigDecorator.class).shutdown();
  }

  private boolean nonEmptyProjectExists(Project.NameKey name) {
    try (Repository r = repoManager.openRepository(name)) {
      return !r.getAllRefsByPeeledObjectId().isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  private ObjectId createNewBranchWithoutPush(String fromBranch, String newBranch)
      throws Exception {
    try (Repository repo = repoManager.openRepository(project);
        RevWalk walk = new RevWalk(repo)) {
      Ref ref = repo.exactRef(fromBranch);
      RevCommit tip = null;
      if (ref != null) {
        tip = walk.parseCommit(ref.getObjectId());
      }
      RefUpdate update = repo.updateRef(newBranch);
      update.setNewObjectId(tip);
      update.update(walk);
      return update.getNewObjectId();
    }
  }
}
