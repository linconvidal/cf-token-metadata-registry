package org.cardanofoundation.tokenmetadata.registry.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.tokenmetadata.registry.model.MappingUpdateDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@Service
@Slf4j
public class GitService {

    @Value("${git.organization:cardano-foundation}")
    private String organization;
    @Value("${git.projectName:cardano-token-registry}")
    private String projectName;
    @Value("${git.mappingsFolder:mappings}")
    private String mappingsFolderName;
    @Value("${git.tmp.folder:/tmp}")
    private String gitTempFolder;
    @Value("${git.forceClone:false}")
    private boolean forceClone;

    @PostConstruct
    void validateConfig() {
        if (gitTempFolder == null || gitTempFolder.isBlank()) {
            log.warn("git.tmp.folder is blank, defaulting to system temp directory");
            gitTempFolder = System.getProperty("java.io.tmpdir");
        }
    }

    public Optional<Path> cloneCardanoTokenRegistryGitRepository() {
        var gitFolder = getGitFolder();

        boolean repoReady;
        if (gitFolder.exists() && (forceClone || !isGitRepo())) {
            log.info("exists and either force clone or not a git repo");
            FileSystemUtils.deleteRecursively(gitFolder);
            repoReady = cloneRepo();
        } else if (gitFolder.exists() && isGitRepo()) {
            log.info("exists and is git repo");
            repoReady = pullRebaseRepo();
        } else {
            repoReady = cloneRepo();
        }

        if (repoReady) {
            return Optional.of(getMappingsFolder());
        } else {
            return Optional.empty();
        }
    }

    private boolean cloneRepo() {
        try {
            var url = String.format("https://github.com/%s/%s.git", organization, projectName);
            var process = new ProcessBuilder()
                    .directory(getGitFolder().getParentFile())
                    .command("git", "clone", url)
                    .start();
            var exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.warn(String.format("It was not possible to clone the %s project", projectName), e);
            return false;
        }
    }

    private boolean pullRebaseRepo() {
        try {
            var process = new ProcessBuilder()
                    .directory(getGitFolder())
                    .command("git", "pull", "--rebase")
                    .start();

            var exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.warn("it was not possible to update repo. cloning from scratch", e);
            return false;
        }
    }

    private boolean isGitRepo() {
        return getGitFolder().toPath().resolve(".git").toFile().exists();
    }

    private File getGitFolder() {
        return new File(String.format("%s/%s", gitTempFolder, projectName));
    }

    private Path getMappingsFolder() {
        return getGitFolder().toPath().resolve(mappingsFolderName);
    }

    public Optional<MappingUpdateDetails> getMappingDetails(File mappingFile) {
        try {
            var process = new ProcessBuilder()
                    .directory(getMappingsFolder().toFile())
                    .command("git", "log", "-n", "1", "--date-order", "--no-merges",
                            "--pretty=format:%aE#-#%aI", mappingFile.getName())
                    .start();

            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = bufferedReader.readLine();

            if (output == null || !output.contains("#-#")) {
                return Optional.empty();
            }

            var parts = output.split("#-#");

            return Optional.of(new MappingUpdateDetails(parts[0], LocalDateTime.parse(parts[1], ISO_OFFSET_DATE_TIME)));

        } catch (IOException e) {
            log.warn(String.format("it was not possible to determine updatedBy and updatedAt for mapping file: %s", mappingFile.getName()), e);
            return Optional.empty();
        }

    }

    public Optional<String> getHeadCommitHash() {
        try {
            var process = new ProcessBuilder()
                    .directory(getGitFolder())
                    .command("git", "rev-parse", "HEAD")
                    .start();
            var exitCode = process.waitFor();
            if (exitCode == 0) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String hash = reader.readLine();
                if (hash != null && hash.trim().length() == 40) {
                    return Optional.of(hash.trim());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get HEAD commit hash", e);
        }
        return Optional.empty();
    }

    public List<Path> getChangedFiles(String fromHash, String toHash) {
        try {
            var process = new ProcessBuilder()
                    .directory(getGitFolder())
                    .command("git", "diff", fromHash + ".." + toHash, "--name-only", "--diff-filter=AM")
                    .start();
            var exitCode = process.waitFor();
            if (exitCode == 0) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                return reader.lines()
                        .filter(line -> line.startsWith(mappingsFolderName + "/"))
                        .filter(line -> line.endsWith(".json"))
                        .map(line -> getGitFolder().toPath().resolve(line))
                        .toList();
            }
        } catch (Exception e) {
            log.warn(String.format("Failed to get changed files between %s and %s", fromHash, toHash), e);
        }
        return List.of();
    }

}
