package org.cardanofoundation.tokenmetadata.registry.service;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitServiceTest {

    private GitService gitService;

    @TempDir
    Path tempDir;

    private Git testRepo;

    @BeforeEach
    void setUp() throws Exception {
        gitService = new GitService();
        setField("organization", "test-org");
        setField("projectName", "test-repo");
        setField("mappingsFolderName", "mappings");
        setField("gitTempFolder", tempDir.toString());
        setField("forceClone", false);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = GitService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(gitService, value);
    }

    private void setGit(Git git) throws Exception {
        Field field = GitService.class.getDeclaredField("git");
        field.setAccessible(true);
        field.set(gitService, git);
    }

    private Git initRepoWithMappings() throws GitAPIException, IOException {
        Path repoDir = tempDir.resolve("test-repo");
        Files.createDirectories(repoDir.resolve("mappings"));
        Git git = Git.init().setDirectory(repoDir.toFile()).call();

        // initial commit so HEAD exists
        Files.writeString(repoDir.resolve("README.md"), "init");
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("initial commit")
                .setAuthor(new PersonIdent("Init", "init@test.com"))
                .call();

        return git;
    }

    private RevCommit addMappingFile(Git git, String fileName, String content, String email) throws Exception {
        Path mappingsDir = git.getRepository().getWorkTree().toPath().resolve("mappings");
        Files.createDirectories(mappingsDir);
        Files.writeString(mappingsDir.resolve(fileName), content);
        git.add().addFilepattern("mappings/" + fileName).call();
        return git.commit().setMessage("add " + fileName)
                .setAuthor(new PersonIdent("Test Author", email))
                .call();
    }

    @Nested
    class GetHeadCommitHash {

        @Test
        void returnsHashFromRepo() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);

            var result = gitService.getHeadCommitHash();

            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(40).matches("[0-9a-f]+");
        }

        @Test
        void returnsEmptyWhenGitIsNull() {
            var result = gitService.getHeadCommitHash();

            assertThat(result).isEmpty();
        }

        @Test
        void matchesActualHeadCommit() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);
            String expectedHash = testRepo.getRepository().resolve("HEAD").name();

            var result = gitService.getHeadCommitHash();

            assertThat(result).hasValue(expectedHash);
        }

        @Test
        void updatesAfterNewCommit() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);
            String firstHash = gitService.getHeadCommitHash().orElseThrow();

            addMappingFile(testRepo, "token1.json", "{}", "dev@test.com");
            String secondHash = gitService.getHeadCommitHash().orElseThrow();

            assertThat(secondHash).isNotEqualTo(firstHash);
        }
    }

    @Nested
    class GetMappingDetails {

        @Test
        void returnsAuthorEmailAndDate() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);
            String email = "author@cardano.org";
            RevCommit commit = addMappingFile(testRepo, "token1.json", "{\"subject\":\"abc\"}", email);

            var result = gitService.getMappingDetails(new File("token1.json"));

            assertThat(result).isPresent();
            assertThat(result.get().updatedBy()).isEqualTo(email);
            LocalDateTime expectedTime = LocalDateTime.ofInstant(
                    commit.getAuthorIdent().getWhenAsInstant(), ZoneOffset.UTC);
            assertThat(result.get().updatedAt()).isEqualTo(expectedTime);
        }

        @Test
        void returnsEmptyForUnknownFile() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);

            var result = gitService.getMappingDetails(new File("nonexistent.json"));

            assertThat(result).isEmpty();
        }

        @Test
        void returnsLatestNonMergeCommit() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);

            addMappingFile(testRepo, "token1.json", "{\"v\":1}", "first@test.com");
            RevCommit latest = addMappingFile(testRepo, "token1.json", "{\"v\":2}", "latest@test.com");

            var result = gitService.getMappingDetails(new File("token1.json"));

            assertThat(result).isPresent();
            assertThat(result.get().updatedBy()).isEqualTo("latest@test.com");
        }

        @Test
        void returnsEmptyWhenGitIsNull() {
            var result = gitService.getMappingDetails(new File("token1.json"));

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class GetChangedFiles {

        @Test
        void returnsAddedJsonFilesInMappingsFolder() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);
            String fromHash = testRepo.getRepository().resolve("HEAD").name();

            addMappingFile(testRepo, "token1.json", "{}", "dev@test.com");
            String toHash = testRepo.getRepository().resolve("HEAD").name();

            List<Path> changed = gitService.getChangedFiles(fromHash, toHash);

            assertThat(changed).hasSize(1);
            assertThat(changed.get(0).getFileName().toString()).isEqualTo("token1.json");
        }

        @Test
        void returnsModifiedFiles() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);
            addMappingFile(testRepo, "token1.json", "{\"v\":1}", "dev@test.com");
            String fromHash = testRepo.getRepository().resolve("HEAD").name();

            addMappingFile(testRepo, "token1.json", "{\"v\":2}", "dev@test.com");
            String toHash = testRepo.getRepository().resolve("HEAD").name();

            List<Path> changed = gitService.getChangedFiles(fromHash, toHash);

            assertThat(changed).hasSize(1);
            assertThat(changed.get(0).getFileName().toString()).isEqualTo("token1.json");
        }

        @Test
        void filtersOutNonJsonFiles() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);
            String fromHash = testRepo.getRepository().resolve("HEAD").name();

            addMappingFile(testRepo, "readme.txt", "text", "dev@test.com");
            addMappingFile(testRepo, "token1.json", "{}", "dev@test.com");
            String toHash = testRepo.getRepository().resolve("HEAD").name();

            List<Path> changed = gitService.getChangedFiles(fromHash, toHash);

            assertThat(changed).hasSize(1);
            assertThat(changed.get(0).getFileName().toString()).isEqualTo("token1.json");
        }

        @Test
        void filtersOutFilesOutsideMappingsFolder() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);
            String fromHash = testRepo.getRepository().resolve("HEAD").name();

            // add file outside mappings
            Path repoDir = testRepo.getRepository().getWorkTree().toPath();
            Files.writeString(repoDir.resolve("other.json"), "{}");
            testRepo.add().addFilepattern("other.json").call();
            testRepo.commit().setMessage("add other.json")
                    .setAuthor(new PersonIdent("Dev", "dev@test.com"))
                    .call();

            addMappingFile(testRepo, "token1.json", "{}", "dev@test.com");
            String toHash = testRepo.getRepository().resolve("HEAD").name();

            List<Path> changed = gitService.getChangedFiles(fromHash, toHash);

            assertThat(changed).hasSize(1);
            assertThat(changed.get(0).getFileName().toString()).isEqualTo("token1.json");
        }

        @Test
        void filtersOutDeletedFiles() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);
            addMappingFile(testRepo, "token1.json", "{}", "dev@test.com");
            addMappingFile(testRepo, "token2.json", "{}", "dev@test.com");
            String fromHash = testRepo.getRepository().resolve("HEAD").name();

            // delete token1.json
            Path repoDir = testRepo.getRepository().getWorkTree().toPath();
            Files.delete(repoDir.resolve("mappings/token1.json"));
            testRepo.rm().addFilepattern("mappings/token1.json").call();
            testRepo.commit().setMessage("delete token1")
                    .setAuthor(new PersonIdent("Dev", "dev@test.com"))
                    .call();
            String toHash = testRepo.getRepository().resolve("HEAD").name();

            List<Path> changed = gitService.getChangedFiles(fromHash, toHash);

            assertThat(changed).isEmpty();
        }

        @Test
        void returnsEmptyForSameHash() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);
            String hash = testRepo.getRepository().resolve("HEAD").name();

            List<Path> changed = gitService.getChangedFiles(hash, hash);

            assertThat(changed).isEmpty();
        }

        @Test
        void returnsEmptyForInvalidHashes() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);

            List<Path> changed = gitService.getChangedFiles(
                    "0000000000000000000000000000000000000000",
                    "1111111111111111111111111111111111111111");

            assertThat(changed).isEmpty();
        }

        @Test
        void returnsMultipleChangedFiles() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);
            String fromHash = testRepo.getRepository().resolve("HEAD").name();

            addMappingFile(testRepo, "token1.json", "{}", "dev@test.com");
            addMappingFile(testRepo, "token2.json", "{}", "dev@test.com");
            addMappingFile(testRepo, "token3.json", "{}", "dev@test.com");
            String toHash = testRepo.getRepository().resolve("HEAD").name();

            List<Path> changed = gitService.getChangedFiles(fromHash, toHash);

            assertThat(changed).hasSize(3)
                    .extracting(p -> p.getFileName().toString())
                    .containsExactlyInAnyOrder("token1.json", "token2.json", "token3.json");
        }
    }

    @Nested
    class ValidateConfig {

        @Test
        void defaultsToSystemTempWhenBlank() throws Exception {
            setField("gitTempFolder", "");

            gitService.validateConfig();

            Field field = GitService.class.getDeclaredField("gitTempFolder");
            field.setAccessible(true);
            assertThat(field.get(gitService)).isEqualTo(System.getProperty("java.io.tmpdir"));
        }

        @Test
        void keepsValueWhenNotBlank() throws Exception {
            String customPath = "/custom/path";
            setField("gitTempFolder", customPath);

            gitService.validateConfig();

            Field field = GitService.class.getDeclaredField("gitTempFolder");
            field.setAccessible(true);
            assertThat(field.get(gitService)).isEqualTo(customPath);
        }
    }

    @Nested
    class Cleanup {

        @Test
        void closesGitWhenNotNull() throws Exception {
            testRepo = initRepoWithMappings();
            setGit(testRepo);

            gitService.cleanup();

            // verify no exception thrown and git is still set (but closed)
            Field field = GitService.class.getDeclaredField("git");
            field.setAccessible(true);
            assertThat(field.get(gitService)).isNotNull();
        }

        @Test
        void handlesNullGitGracefully() {
            // should not throw
            gitService.cleanup();
        }
    }

    @Nested
    class CloneCardanoTokenRegistryGitRepository {

        @Test
        void pullsWhenRepoAlreadyExists() throws Exception {
            // set up a local "remote" and clone from it
            Path remoteDir = tempDir.resolve("remote-repo");
            Git remoteGit = Git.init().setBare(true).setDirectory(remoteDir.toFile()).call();

            // create a non-bare repo, add content, push to remote
            Path seedDir = tempDir.resolve("seed");
            Git seedGit = Git.init().setDirectory(seedDir.toFile()).call();
            Files.createDirectories(seedDir.resolve("mappings"));
            Files.writeString(seedDir.resolve("mappings/token.json"), "{}");
            seedGit.add().addFilepattern(".").call();
            seedGit.commit().setMessage("init")
                    .setAuthor(new PersonIdent("Seed", "seed@test.com"))
                    .call();
            seedGit.push().setRemote(remoteDir.toUri().toString()).call();
            seedGit.close();

            // clone from local remote into the expected directory
            Path repoDir = tempDir.resolve("test-repo");
            Git clonedGit = Git.cloneRepository()
                    .setURI(remoteDir.toUri().toString())
                    .setDirectory(repoDir.toFile())
                    .call();
            clonedGit.close();

            var result = gitService.cloneCardanoTokenRegistryGitRepository();

            assertThat(result).isPresent();
            assertThat(result.get().toFile()).exists();
            assertThat(result.get().getFileName().toString()).isEqualTo("mappings");
        }

        @Test
        void returnsEmptyWhenCloneFails() {
            // organization/project point to non-existent remote, clone will fail
            var result = gitService.cloneCardanoTokenRegistryGitRepository();

            assertThat(result).isEmpty();
        }

        @Test
        void forceCloneDeletesExistingNonGitDir() throws Exception {
            setField("forceClone", true);

            // create a non-git directory at the expected path
            Path repoDir = tempDir.resolve("test-repo");
            Files.createDirectories(repoDir.resolve("mappings"));
            Files.writeString(repoDir.resolve("some-file.txt"), "stale");

            // clone will fail (bad remote), but the directory should have been deleted
            var result = gitService.cloneCardanoTokenRegistryGitRepository();

            assertThat(result).isEmpty();
            // the stale file should be gone (directory was deleted before clone attempt)
            assertThat(repoDir.resolve("some-file.txt")).doesNotExist();
        }
    }
}
