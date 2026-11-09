package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The infrastructure code is pinned and locked, keeps its state remote, locked and encrypted, follows its conventions,
 * and is checked by CI with no credential and no backend ({@code P15-TSK-013}; ADR-0121; {@code THREAT_MODEL.md} T11
 * and T30).
 *
 * <p>The tools themselves ({@code fmt}, {@code tflint}, {@code trivy config}, {@code validate}, {@code terraform test})
 * run in CI's {@code iac} job through {@code infra/scripts/terraform-check.sh}, which also proves each refuses a planted
 * defect. This class holds what those tools do not read: that every pin is exact and agrees with its lock file and with
 * the image that checks it, that the backend is never initialised and no credential is anywhere, that no test can reach
 * a cloud, and the two conventions no tool knows — {@code prevent_destroy} on the financial stores and their keys, and
 * no resource that writes a secret value into state.
 *
 * <p>It reads HCL with a small reader of its own (blocks, attributes, strings, templates, heredocs, comments) that fails
 * on what it cannot read rather than skipping it. {@link #theRulesRefuseTheirPlants} runs every rule over a planted
 * violation, so a rule that reads nothing cannot pass.
 *
 * <p>The data tier ({@code P15-TSK-015}) reads its topics, its ACLs and its brokers' posture from {@code infra/kafka/}.
 * {@code terraform test} proves that with a fixture catalogue; {@link #theCatalogueHasNoCopyUnderTerraform} refuses a
 * byte-identical copy, which a value comparison cannot see, and {@link #anOpenDecisionHasNoDefault} holds the audit
 * archive's lock to a period nobody has chosen.
 */
@Tag("architecture")
@DisplayName("the Terraform is pinned, locked and checked without a credential or a backend (P15-TSK-013)")
class TerraformIsPinnedAndCheckedTest {

    private static final String TERRAFORM = "infra/terraform";
    private static final String ENVIRONMENTS = TERRAFORM + "/environments";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String SCRIPT = "infra/scripts/terraform-check.sh";
    private static final String WORKFLOW = ".github/workflows/ci.yml";
    private static final String LOCK_FILE = ".terraform.lock.hcl";

    /** ADR-0121's environments: a root module each. */
    private static final Set<String> ENVIRONMENT_ROOTS = Set.of("staging", "production");

    /** An exact version: {@code 6.72.0} or {@code = 6.72.0}, never a range. */
    private static final Pattern EXACT = Pattern.compile("^=?\\s*(\\d+\\.\\d+\\.\\d+)$");

    /**
     * The types whose destruction loses the financial record, its backups or the keys its encrypted fields need
     * (ADR-0121 decision 4: the database, its backups and every KMS key). Each must declare {@code prevent_destroy}.
     */
    static final Set<String> GUARDED = Set.of(
            "aws_db_instance", "aws_rds_cluster", "aws_db_snapshot", "aws_db_cluster_snapshot",
            "aws_db_instance_automated_backups_replication", "aws_backup_vault", "aws_kms_key", "aws_kms_replica_key",
            "aws_kms_external_key");

    /**
     * Resource and data types whose state holds a secret value they generate or read (ADR-0121 decision 3: the secrets
     * manager generates credentials; Terraform creates only the entries and the policies).
     */
    static final Set<String> SECRET_IN_STATE = Set.of(
            "resource random_password", "resource tls_private_key", "resource aws_iam_access_key",
            "resource aws_iam_user_login_profile", "resource aws_secretsmanager_secret_version",
            "resource aws_kms_ciphertext", "data aws_secretsmanager_secret_version", "data aws_kms_secrets");

    /** A database resource whose password argument would put the value in state; {@code manage_master_user_password}. */
    private static final Set<String> DATABASES = Set.of("aws_db_instance", "aws_rds_cluster");

    /** An argument that is a credential, refused in a backend and in a provider block. */
    static final Set<String> CREDENTIAL_ARGUMENTS = Set.of(
            "access_key", "secret_key", "token", "profile", "shared_credentials_files", "shared_config_files",
            "sasl_username", "sasl_password", "client_key", "client_key_file", "client_key_passphrase",
            "sasl_aws_access_key", "sasl_aws_secret_key", "sasl_aws_token");

    /** Variables whose value is a decision nobody has taken yet: a default would take it silently. */
    private static final Set<String> OPEN_DECISIONS =
            Set.of("audit_archive_lock", "kafka_provisioning_stage", "provisioning_stage");

    private static final String KAFKA = "infra/kafka";

    private static final Pattern CATALOGUED_TOPIC = Pattern.compile("(?m)^  (finapp\\.[a-z]+):\\s");

    /** Terraform subcommands that touch a backend, a state or an account; the check script may run none. */
    private static final Pattern FORBIDDEN_SUBCOMMAND = Pattern.compile(
            "\\b(apply|plan|import|refresh|destroy|state|taint|untaint|force-unlock|login|logout|workspace)\\b");

    private static final Pattern JOB = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");

    private static Path root;
    private static Map<String, String> files;

    @BeforeAll
    static void readTheTree() {
        root = EvidenceCitations.repositoryRoot();
        files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root.resolve(TERRAFORM))) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tf")
                            || path.getFileName().toString().endsWith(".tftest.hcl")
                            || path.getFileName().toString().equals(LOCK_FILE))
                    .forEach(path -> files.put(
                            root.relativize(path).toString().replace('\\', '/'), EvidenceCitations.read(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("every environment root pins Terraform to the checking image's version and every provider exactly,"
            + " the same in every root")
    void everyRootPinsTerraformAndEveryProviderExactly() {
        assertThat(roots()).as("ADR-0121's environment roots").isEqualTo(new TreeSet<>(ENVIRONMENT_ROOTS));
        String terraform = pinned("TERRAFORM_VERSION");
        Map<String, Map<String, String>> providersByRoot = new TreeMap<>();
        List<String> violations = new ArrayList<>();
        for (String environment : roots()) {
            String directory = ENVIRONMENTS + "/" + environment;
            violations.addAll(pinViolations(directory, terraformFiles(directory), terraform));
            providersByRoot.put(environment, requiredProviders(terraformFiles(directory)));
        }
        assertThat(violations).as("a range, a missing pin or a version other than the checking image's").isEmpty();
        assertThat(providersByRoot.values())
                .as("every root's providers were read")
                .allSatisfy(providers -> assertThat(providers).isNotEmpty());
        assertThat(new TreeSet<>(providersByRoot.values().stream().map(Map::toString).toList()))
                .as("staging rehearses production's providers at production's versions: %s", providersByRoot)
                .hasSize(1);
    }

    @Test
    @DisplayName("every environment root's lock file holds exactly its required providers at their pinned versions")
    void everyLockFileHoldsThePinnedVersions() {
        List<String> violations = new ArrayList<>();
        for (String environment : roots()) {
            String directory = ENVIRONMENTS + "/" + environment;
            String lock = files.get(directory + "/" + LOCK_FILE);
            assertThat(lock).as("%s/%s is committed", directory, LOCK_FILE).isNotNull();
            violations.addAll(lockViolations(directory, requiredProviders(terraformFiles(directory)), lock));
        }
        assertThat(violations).as("the lock file and the pins disagree").isEmpty();
    }

    @Test
    @DisplayName("every child module that requires a provider commits a lock file at the exact version the roots pin")
    void everyModuleLockFileHoldsTheRootsVersions() {
        // terraform-check.sh runs every init with -lockfile=readonly, so a module requiring a provider needs a lock
        // file; held at the roots' version so that its tests run against the provider the roots use.
        Map<String, String> pinned = requiredProviders(terraformFiles(ENVIRONMENTS + "/" + roots().iterator().next()));
        List<String> violations = new ArrayList<>();
        int modules = 0;
        for (String directory : directories()) {
            Map<String, String> required = requiredProviders(terraformFiles(directory));
            if (directory.startsWith(ENVIRONMENTS + "/") || required.isEmpty()) {
                continue;
            }
            modules++;
            violations.addAll(moduleLockViolations(directory, required, pinned, files.get(directory + "/" + LOCK_FILE)));
        }
        assertThat(modules).as("the modules requiring a provider were read").isGreaterThanOrEqualTo(2);
        assertThat(violations).as("a module tested against another provider than its roots use").isEmpty();
    }

    @Test
    @DisplayName("every environment keeps its state in its own S3 key, encrypted and locked, and no backend or provider"
            + " block holds a credential")
    void everyEnvironmentKeepsItsStateRemoteLockedAndEncrypted() {
        List<String> violations = new ArrayList<>();
        Set<String> keys = new TreeSet<>();
        for (String environment : roots()) {
            String directory = ENVIRONMENTS + "/" + environment;
            violations.addAll(backendViolations(directory, environment, terraformFiles(directory), keys));
        }
        files.forEach((path, text) -> {
            if (path.endsWith(".tf")) {
                violations.addAll(credentialViolations(path, text));
            }
        });
        assertThat(violations).as("state that is local, shared, unencrypted or unlocked, or a credential").isEmpty();
        assertThat(keys).as("one state key per environment").hasSize(ENVIRONMENT_ROOTS.size());
    }

    @Test
    @DisplayName("CI's iac job runs on every change and its script runs every check over the tree and refuses a plant"
            + " with each")
    void ciRunsEveryCheckOverTheTreeAndItsPlants() {
        List<String> job = job("iac");
        assertThat(job)
                .as("the iac job runs the script a developer runs")
                .anySatisfy(line -> assertThat(line.trim()).isEqualTo("run: ./infra/scripts/terraform-check.sh"));
        assertThat(job)
                .as("the iac job is not gated to a schedule or an event: every change is checked")
                .noneSatisfy(line -> assertThat(line.trim()).startsWith("if:"));
        assertThat(workflowLines())
                .as("the workflow runs on every pull request")
                .anySatisfy(line -> assertThat(line.trim()).isEqualTo("pull_request:"));
        List<String> script = scriptLines();
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("fmt", "fmt_on \"$TREE\"");
        checks.put("tflint", "tflint_on \"$TREE\"");
        checks.put("trivy config", "trivy_on \"$TREE\"");
        checks.put("validate and test", "terraform_on \"$TREE\"");
        checks.forEach((check, call) -> assertThat(script)
                .as("the script runs %s over the whole tree", check)
                .anySatisfy(line -> assertThat(line).startsWith("check ").contains(call)));
        assertThat(String.join("\n", script))
                .as("each tool's command")
                .contains("terraform fmt -check -recursive")
                .contains("--recursive --chdir=")
                .contains("config --skip-check-update --severity HIGH,CRITICAL --exit-code 1")
                .contains(" validate ")
                .contains(" test ");
        Map<String, String> plants = new TreeMap<>();
        for (String line : script) {
            Matcher refuses = Pattern.compile("^refuses (\\S+) \"[^\"]+\" (\\w+)_on ").matcher(line);
            if (refuses.find()) {
                plants.put(refuses.group(1), refuses.group(2));
            }
        }
        assertThat(plants)
                .as("a plant per check, each refused by the same function that checks the tree")
                .containsEntry("unformatted", "fmt")
                .containsEntry("unpinned-provider", "tflint")
                .containsEntry("camel-case", "tflint")
                .containsEntry("public-bucket", "trivy")
                .containsEntry("invalid", "terraform")
                .containsEntry("untagged", "terraform")
                .containsEntry("public-endpoint", "terraform")
                .containsEntry("public-workload-subnet", "terraform")
                .containsEntry("cluster-on-edge-tier", "terraform")
                .containsEntry("no-flow-logs", "terraform")
                .containsEntry("no-audit-log", "terraform")
                .containsEntry("no-etcd-encryption", "terraform")
                .containsEntry("two-zones", "terraform")
                .containsEntry("multi-az-off", "terraform")
                .containsEntry("force-ssl-off", "terraform")
                .containsEntry("database-public", "terraform")
                .containsEntry("backup-key-shared", "terraform")
                .containsEntry("max-connections-restated", "terraform")
                .containsEntry("unclean-election-on", "terraform")
                .containsEntry("auto-create-on", "terraform")
                .containsEntry("min-insync-one", "terraform")
                .containsEntry("brokers-plaintext", "terraform")
                .containsEntry("brokers-unauthenticated", "terraform")
                .containsEntry("scram-unprefixed", "terraform")
                .containsEntry("topics-from-a-copy", "terraform")
                .containsEntry("public-archive", "terraform")
                .containsEntry("no-object-lock", "terraform");
    }

    @Test
    @DisplayName("CI's iac job holds no credential, and its script initialises no backend, runs no state-touching"
            + " command and hands no container the host's environment")
    void ciHoldsNoCredentialAndInitialisesNoBackend() {
        assertThat(job("iac"))
                .as("the iac job declares no environment, permission or secret of its own")
                .noneSatisfy(line -> assertThat(line.trim()).matches("^(env|permissions|secrets)\\s*:.*"))
                .noneSatisfy(line -> assertThat(line).contains("secrets."));
        assertThat(scriptViolations(scriptLines())).as("the check script reaching a backend or an account").isEmpty();
    }

    @Test
    @DisplayName("every terraform test file mocks every provider its directory requires and configures no real one")
    void everyTestMocksEveryProvider() {
        List<String> violations = new ArrayList<>();
        int tests = 0;
        for (String directory : directories()) {
            // The local names (aws), which mock_provider takes, not the sources (hashicorp/aws).
            Set<String> required = requiredProviderEntries(terraformFiles(directory)).keySet();
            for (Map.Entry<String, String> file : files.entrySet()) {
                if (file.getKey().startsWith(directory + "/tests/") && file.getKey().endsWith(".tftest.hcl")) {
                    tests++;
                    violations.addAll(mockViolations(file.getKey(), required, file.getValue()));
                }
            }
        }
        assertThat(tests).as("the test files were read: one per root and module").isGreaterThanOrEqualTo(3);
        assertThat(violations).as("a test that could reach a cloud").isEmpty();
    }

    @Test
    @DisplayName("every environment root takes its name prefix and tags from the conventions module, as itself, and its"
            + " provider applies only to its own account")
    void everyEnvironmentNamesAndTagsThroughTheConventions() {
        List<String> violations = new ArrayList<>();
        for (String environment : roots()) {
            String directory = ENVIRONMENTS + "/" + environment;
            violations.addAll(conventionViolations(directory, environment, terraformFiles(directory)));
        }
        assertThat(violations).as("a root that names or tags itself outside the conventions").isEmpty();
    }

    @Test
    @DisplayName("every resource of a guarded type (the database, its backups, every KMS key) declares prevent_destroy")
    void everyGuardedResourcePreventsItsDestruction() {
        List<String> violations = new ArrayList<>();
        files.forEach((path, text) -> {
            if (path.endsWith(".tf")) {
                violations.addAll(destroyViolations(path, text));
            }
        });
        assertThat(files.keySet().stream().filter(path -> path.endsWith(".tf")))
                .as("the tree's .tf files were read")
                .hasSizeGreaterThanOrEqualTo(10);
        assertThat(violations).as("a plan could destroy the financial record or a key its fields need").isEmpty();
    }

    @Test
    @DisplayName("no resource or data source writes a secret value into state, and no database takes a password")
    void noResourceWritesASecretIntoState() {
        List<String> violations = new ArrayList<>();
        files.forEach((path, text) -> {
            if (path.endsWith(".tf")) {
                violations.addAll(secretViolations(path, text));
            }
        });
        assertThat(violations).as("a secret value in Terraform state").isEmpty();
    }

    @Test
    @DisplayName("nothing under infra/terraform copies the topic catalogue or the brokers' posture: no such file, and no"
            + " .tf naming a catalogued topic")
    void theCatalogueHasNoCopyUnderTerraform() {
        Set<String> topics = cataloguedTopics(EvidenceCitations.read(root.resolve(KAFKA + "/topics.yaml")));
        assertThat(topics).as("the catalogue's topics were read").hasSizeGreaterThanOrEqualTo(10);
        Set<String> fileNames = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root.resolve(TERRAFORM))) {
            walk.filter(Files::isRegularFile).forEach(path -> fileNames.add(path.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> tf = new TreeMap<>();
        files.forEach((path, text) -> {
            if (path.endsWith(".tf")) {
                tf.put(path, text);
            }
        });
        assertThat(copyViolations(fileNames, tf, topics))
                .as("a second truth nothing holds to the code, which TopicCatalogueTest reads infra/kafka for")
                .isEmpty();
    }

    @Test
    @DisplayName("a variable holding a decision nobody has taken has no default, in every root and module that declares it")
    void anOpenDecisionHasNoDefault() {
        List<String> violations = new ArrayList<>();
        int read = 0;
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (file.getKey().endsWith(".tf")) {
                read += (int) blocksOfType(file.getValue(), "variable").stream()
                        .filter(variable -> OPEN_DECISIONS.contains(variable.labels().get(0)))
                        .count();
                violations.addAll(defaultViolations(file.getKey(), file.getValue()));
            }
        }
        assertThat(read).as("the two roots and two modules declaring these were read").isGreaterThanOrEqualTo(6);
        assertThat(violations).as("a default that takes a decision no one has taken").isEmpty();
    }

    @Test
    @DisplayName("every rule above refuses a planted violation")
    void theRulesRefuseTheirPlants() {
        Map<String, String> pinned = Map.of("versions.tf", """
                terraform {
                  required_version = ">= 1.17.1"
                  required_providers {
                    aws = {
                      source  = "hashicorp/aws"
                      version = "~> 6.72"
                    }
                  }
                }
                """);
        assertThat(pinViolations("plant", pinned, "1.17.1")).hasSize(2);

        Map<String, String> exact = Map.of("versions.tf", """
                terraform {
                  required_version = "1.17.1"
                  required_providers {
                    aws = { source = "hashicorp/aws", version = "6.72.0" }
                  }
                }
                """);
        assertThat(pinViolations("plant", exact, "1.17.1")).isEmpty();
        assertThat(lockViolations("plant", requiredProviders(exact), """
                provider "registry.terraform.io/hashicorp/aws" {
                  version     = "6.71.0"
                  constraints = "6.71.0"
                  hashes = ["h1:x=", "zh:y"]
                }
                """)).as("a lock file at another version").isNotEmpty();
        assertThat(lockViolations("plant", requiredProviders(exact), """
                provider "registry.terraform.io/hashicorp/aws" {
                  version     = "6.72.0"
                  constraints = "6.72.0"
                  hashes = ["h1:x=", "zh:y"]
                }
                provider "registry.terraform.io/hashicorp/random" {
                  version = "3.7.2"
                  hashes  = ["h1:x=", "zh:y"]
                }
                """)).as("a provider locked that no root requires").isNotEmpty();

        Map<String, String> moduleRequires = Map.of("hashicorp/aws", ">= 6.72.0");
        Map<String, String> rootsPin = Map.of("hashicorp/aws", "6.72.0");
        assertThat(moduleLockViolations("plant", moduleRequires, rootsPin, null))
                .as("a module requiring a provider with no lock file")
                .hasSize(1);
        assertThat(moduleLockViolations("plant", moduleRequires, rootsPin, """
                provider "registry.terraform.io/hashicorp/aws" {
                  version     = "6.71.0"
                  constraints = ">= 6.72.0"
                  hashes = ["h1:x=", "zh:y"]
                }
                """)).as("a module locked at another version than the roots pin").hasSize(1);
        assertThat(moduleLockViolations("plant", moduleRequires, rootsPin, """
                provider "registry.terraform.io/hashicorp/aws" {
                  version     = "6.72.0"
                  constraints = ">= 6.72.0"
                  hashes = ["h1:x=", "zh:y"]
                }
                """)).as("a module locked as the roots are").isEmpty();
        assertThat(moduleLockViolations("plant", Map.of("hashicorp/awscc", ">= 1.0.0"), rootsPin, """
                provider "registry.terraform.io/hashicorp/awscc" {
                  version     = "1.0.0"
                  constraints = ">= 1.0.0"
                  hashes = ["h1:x=", "zh:y"]
                }
                """)).as("a module requiring a provider no root pins").hasSize(1);

        assertThat(backendViolations("plant", "staging", Map.of("backend.tf", """
                terraform {
                  backend "s3" {
                    key        = "finapp/staging/terraform.tfstate"
                    encrypt    = false
                    access_key = "AKIAPLANT"
                  }
                }
                """), new TreeSet<>())).as("unencrypted, unlocked, and a credential").hasSize(3);
        assertThat(credentialViolations("plant.tf", """
                provider "aws" {
                  profile = "operator"
                }
                """)).as("a credential in a provider block").hasSize(1);

        assertThat(scriptViolations(List.of(
                "terraform -chdir=x init -input=false",
                "terraform -chdir=x apply -auto-approve",
                "MSYS_NO_PATHCONV=1 docker run --rm -e AWS_PROFILE -v \"$DIR:/repo/infra\" image")))
                .as("an init with a backend, an apply, a host variable and a writable tree")
                .hasSize(4);

        assertThat(mockViolations("plant.tftest.hcl", Set.of("aws"), """
                provider "aws" {
                  region = "eu-central-1"
                }
                run "real" {
                  command = plan
                }
                """)).as("a test configuring the real provider, unmocked").hasSize(2);

        assertThat(conventionViolations("plant", "production", Map.of("main.tf", """
                module "conventions" {
                  source      = "../../modules/conventions"
                  environment = "staging"
                }
                provider "aws" {
                  region = var.region
                }
                """))).as("production tagged as staging, and a provider neither tagged nor confined to its account")
                .hasSize(3);
        assertThat(conventionViolations("plant", "production", Map.of("main.tf", """
                module "conventions" {
                  source      = "../../modules/conventions"
                  environment = "production"
                }
                provider "aws" {
                  allowed_account_ids = [var.account_id]
                  default_tags {
                    tags = module.conventions.tags
                  }
                }
                """))).as("a root as every root should be").isEmpty();

        assertThat(destroyViolations("plant.tf", """
                resource "aws_kms_key" "unguarded" {
                  description = "no lifecycle at all"
                }
                resource "aws_db_instance" "allowed_to_go" {
                  lifecycle {
                    prevent_destroy = false
                  }
                }
                resource "aws_kms_key" "guarded" {
                  # a } in a comment and "}" in a string must not end the block
                  description = "the key's } brace"
                  lifecycle {
                    prevent_destroy = true
                  }
                }
                resource "aws_s3_bucket" "unguarded_by_design" {
                }
                """)).as("two guarded types without prevent_destroy = true, and only those")
                .hasSize(2)
                .anySatisfy(violation -> assertThat(violation).contains("aws_kms_key.unguarded "))
                .anySatisfy(violation -> assertThat(violation).contains("aws_db_instance.allowed_to_go "));

        assertThat(secretViolations("plant.tf", """
                resource "random_password" "database" {
                  length = 32
                }
                data "aws_secretsmanager_secret_version" "read_back" {
                  secret_id = "x"
                }
                resource "aws_db_instance" "ledger" {
                  password = "${random_password.database.result}"
                  lifecycle {
                    prevent_destroy = true
                  }
                }
                resource "aws_db_instance" "managed" {
                  manage_master_user_password = true
                  lifecycle {
                    prevent_destroy = true
                  }
                }
                """)).as("a generated password, a secret read back and a database given the value").hasSize(3);

        assertThat(blocks("""
                locals {
                  text = <<-EOT
                    an unbalanced } inside a heredoc
                  EOT
                  nested = "${join(",", ["}"])}"
                }
                /* a comment with { */
                // another with }
                output "after" {
                  value = local.text
                }
                """)).as("the reader keeps every block whatever its strings, heredocs and comments hold")
                .extracting(Block::type)
                .containsExactly("locals", "output");

        assertThat(credentialViolations("plant.tf", """
                provider "kafka" {
                  bootstrap_servers = ["broker:9096"]
                  sasl_password     = "planted"
                }
                """)).as("a Kafka credential in a provider block").hasSize(1);

        assertThat(conventionViolations("plant", "production", Map.of("main.tf", """
                module "conventions" {
                  source      = "../../modules/conventions"
                  environment = "production"
                }
                provider "kafka" {
                  tls_enabled = true
                }
                """))).as("a provider with no tags or accounts to carry is no violation of the AWS rules").isEmpty();

        assertThat(scriptViolations(List.of(
                "terraform -chdir=x init -backend=false",
                "MSYS_NO_PATHCONV=1 docker run --rm -v \"$REPO/deploy:/repo/deploy\" image")))
                .as("any repository directory mounted writable")
                .hasSize(1);

        assertThat(cataloguedTopics("topics:\n  finapp.ledger: domain-events\n  finapp.risk: domain-events\n"))
                .containsExactly("finapp.ledger", "finapp.risk");
        assertThat(copyViolations(
                Set.of("topics.yaml", "broker.properties", "fixture-topics.yaml", "main.tf"),
                Map.of("main.tf", "locals {\n  topics = [\"finapp.ledger\"]\n}\n"),
                Set.of("finapp.ledger")))
                .as("two copied files and a .tf naming a catalogued topic, and not the fixture")
                .hasSize(3);
        assertThat(copyViolations(
                Set.of(),
                Map.of("main.tf", "locals {\n  topics = <<-EOT\n    finapp.ledger\n  EOT\n  other = \"finapp.ledgers\"\n}\n"),
                Set.of("finapp.ledger")))
                .as("a copy in a heredoc is caught, and a longer name is not mistaken for it")
                .hasSize(1);

        assertThat(defaultViolations("plant.tf", """
                variable "audit_archive_lock" {
                  type    = object({ mode = string, days = number })
                  default = { mode = "GOVERNANCE", days = 2555 }
                }
                variable "provisioning_stage" {
                  type = string
                }
                variable "unrelated" {
                  type    = number
                  default = 3
                }
                """)).as("the decision given a default, and neither the one without nor an ordinary variable")
                .hasSize(1)
                .allSatisfy(violation -> assertThat(violation).contains("audit_archive_lock"));
    }

    // --- the rules, each over text so theRulesRefuseTheirPlants can run it on a plant -----------------------------

    /** The topic names in the catalogue's text: its two-space keys, which the catalogue's scheme starts {@code finapp.}. */
    static Set<String> cataloguedTopics(String catalogue) {
        Set<String> topics = new TreeSet<>();
        Matcher topic = CATALOGUED_TOPIC.matcher(catalogue);
        while (topic.find()) {
            topics.add(topic.group(1));
        }
        return topics;
    }

    static List<String> copyViolations(Set<String> fileNames, Map<String, String> tf, Set<String> topics) {
        List<String> violations = new ArrayList<>();
        for (String name : List.of("topics.yaml", "broker.properties")) {
            if (fileNames.contains(name)) {
                violations.add("a file named " + name + " under the Terraform tree is a copy of " + KAFKA + "/" + name);
            }
        }
        // Any mention, not only a quoted one: a copy can sit in a heredoc as readily as in a list.
        tf.forEach((path, text) -> topics.stream()
                .filter(topic -> Pattern.compile("(?<![\\w.-])" + Pattern.quote(topic) + "(?![\\w.-])")
                        .matcher(text)
                        .find())
                .forEach(topic -> violations.add(path + " names the catalogued topic " + topic)));
        return violations;
    }

    static List<String> defaultViolations(String path, String text) {
        List<String> violations = new ArrayList<>();
        for (Block variable : blocksOfType(text, "variable")) {
            if (OPEN_DECISIONS.contains(variable.labels().get(0)) && attributes(variable.body()).containsKey("default")) {
                violations.add(path + ": variable " + variable.labels().get(0)
                        + " has a default, so a plan would take a decision nobody has taken");
            }
        }
        return violations;
    }

    static List<String> pinViolations(String directory, Map<String, String> tf, String terraformVersion) {
        List<String> violations = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        for (Map.Entry<String, String> file : tf.entrySet()) {
            for (Block terraform : blocksOfType(file.getValue(), "terraform")) {
                String version = attributes(terraform.body()).get("required_version");
                if (version != null) {
                    versions.add(version);
                }
            }
        }
        if (versions.size() != 1) {
            violations.add(directory + ": required_version is declared " + versions.size() + " times, not once");
        }
        for (String version : versions) {
            Matcher exact = EXACT.matcher(unquoted(version));
            if (!exact.matches() || !exact.group(1).equals(terraformVersion)) {
                violations.add(directory + ": required_version " + version + " is not exactly " + terraformVersion
                        + ", the version of the image that checks it");
            }
        }
        requiredProviderEntries(tf).forEach((name, attributes) -> {
            String source = attributes.get("source");
            String version = attributes.get("version");
            if (source == null) {
                violations.add(directory + ": provider " + name + " names no source");
            }
            if (version == null || !EXACT.matcher(unquoted(version)).matches()) {
                violations.add(directory + ": provider " + name + " is not pinned exactly: " + version);
            }
        });
        return violations;
    }

    static List<String> lockViolations(String directory, Map<String, String> required, String lock) {
        List<String> violations = new ArrayList<>();
        Map<String, Map<String, String>> locked = new TreeMap<>();
        for (Block provider : blocksOfType(lock, "provider")) {
            locked.put(provider.labels().get(0), attributes(provider.body()));
        }
        Map<String, String> expected = new TreeMap<>();
        required.forEach((source, version) -> expected.put(address(source), version));
        if (!locked.keySet().equals(expected.keySet())) {
            violations.add(directory + ": the lock file holds " + locked.keySet() + ", the root requires "
                    + expected.keySet());
        }
        expected.forEach((address, version) -> {
            Map<String, String> entry = locked.get(address);
            if (entry == null) {
                return;
            }
            String pin = EXACT.matcher(version).replaceAll("$1");
            if (!pin.equals(unquoted(entry.getOrDefault("version", "")))) {
                violations.add(directory + ": " + address + " is locked at " + entry.get("version") + ", pinned at "
                        + pin);
            }
            if (!unquoted(entry.getOrDefault("constraints", "")).equals(version)) {
                violations.add(directory + ": " + address + " was locked under " + entry.get("constraints")
                        + ", the pin is now " + version);
            }
            String hashes = entry.getOrDefault("hashes", "");
            if (!hashes.contains("\"h1:") || !hashes.contains("\"zh:")) {
                violations.add(directory + ": " + address + " holds no h1: and zh: checksums");
            }
        });
        return violations;
    }

    /** A child module's lock file: every provider it requires, at the exact version the roots pin, with its checksums. */
    static List<String> moduleLockViolations(
            String directory, Map<String, String> required, Map<String, String> rootsPin, String lock) {
        List<String> violations = new ArrayList<>();
        if (lock == null) {
            violations.add(directory + ": no " + LOCK_FILE + ", so init -lockfile=readonly cannot install " + required);
            return violations;
        }
        Map<String, Map<String, String>> locked = new TreeMap<>();
        for (Block provider : blocksOfType(lock, "provider")) {
            locked.put(provider.labels().get(0), attributes(provider.body()));
        }
        required.forEach((source, constraint) -> {
            String pin = rootsPin.get(source);
            if (pin == null) {
                violations.add(directory + ": requires " + source + ", which no environment root pins");
                return;
            }
            Map<String, String> entry = locked.get(address(source));
            if (entry == null) {
                violations.add(directory + ": the lock file does not hold " + source);
                return;
            }
            String version = EXACT.matcher(pin).replaceAll("$1");
            if (!version.equals(unquoted(entry.getOrDefault("version", "")))) {
                violations.add(directory + ": " + source + " is locked at " + entry.get("version")
                        + ", the roots pin " + version + "; regenerate this module's lock file");
            }
            String hashes = entry.getOrDefault("hashes", "");
            if (!hashes.contains("\"h1:") || !hashes.contains("\"zh:")) {
                violations.add(directory + ": " + source + " holds no h1: and zh: checksums");
            }
        });
        return violations;
    }

    static List<String> backendViolations(
            String directory, String environment, Map<String, String> tf, Set<String> keys) {
        List<String> violations = new ArrayList<>();
        List<Block> backends = new ArrayList<>();
        for (String text : tf.values()) {
            for (Block terraform : blocksOfType(text, "terraform")) {
                backends.addAll(blocks(terraform.body()).stream().filter(b -> b.type().equals("backend")).toList());
            }
        }
        if (backends.size() != 1 || !backends.get(0).labels().equals(List.of("s3"))) {
            violations.add(directory + ": not exactly one s3 backend: " + backends);
            return violations;
        }
        Map<String, String> backend = attributes(backends.get(0).body());
        if (!"true".equals(backend.get("encrypt"))) {
            violations.add(directory + ": the state is not encrypted (encrypt = true)");
        }
        if (!"true".equals(backend.get("use_lockfile"))) {
            violations.add(directory + ": the state is not locked (use_lockfile = true)");
        }
        String key = unquoted(backend.getOrDefault("key", ""));
        if (!key.contains("/" + environment + "/")) {
            violations.add(directory + ": the state key " + key + " does not name " + environment);
        }
        keys.add(key);
        for (String argument : backend.keySet()) {
            if (CREDENTIAL_ARGUMENTS.contains(argument)) {
                violations.add(directory + ": the backend holds a credential argument, " + argument);
            }
        }
        return violations;
    }

    static List<String> credentialViolations(String path, String text) {
        List<String> violations = new ArrayList<>();
        for (Block provider : blocksOfType(text, "provider")) {
            for (String argument : attributes(provider.body()).keySet()) {
                if (CREDENTIAL_ARGUMENTS.contains(argument)) {
                    violations.add(path + ": provider " + provider.labels() + " holds a credential, " + argument);
                }
            }
        }
        return violations;
    }

    static List<String> scriptViolations(List<String> lines) {
        List<String> violations = new ArrayList<>();
        boolean initialises = false;
        for (String line : lines) {
            if (Pattern.compile("\\binit\\b").matcher(line).find()) {
                initialises = true;
                if (!line.contains("-backend=false")) {
                    violations.add("an init that may initialise a backend: " + line.trim());
                }
            }
            Matcher forbidden = FORBIDDEN_SUBCOMMAND.matcher(line);
            if (forbidden.find()) {
                violations.add("a command touching a backend, a state or an account (" + forbidden.group(1) + "): "
                        + line.trim());
            }
            if (line.contains("docker run")) {
                if (Pattern.compile("\\s(-e|--env|--env-file)(\\s|=)").matcher(line).find()) {
                    violations.add("a container handed the host's environment: " + line.trim());
                }
            }
            if (Pattern.compile(":/repo/\\w+(?!\\w|:ro)").matcher(line).find()) {
                violations.add("the tree mounted writable: " + line.trim());
            }
        }
        if (!initialises && lines.size() > 10) {
            violations.add("no init was read, so the backend rule read nothing");
        }
        return violations;
    }

    static List<String> mockViolations(String path, Set<String> requiredNames, String test) {
        List<String> violations = new ArrayList<>();
        Set<String> mocked = new TreeSet<>();
        for (Block block : blocks(test)) {
            if (block.type().equals("mock_provider")) {
                mocked.add(block.labels().get(0));
            }
            if (block.type().equals("provider")) {
                violations.add(path + ": configures the real provider " + block.labels());
            }
        }
        for (String name : requiredNames) {
            if (!mocked.contains(name)) {
                violations.add(path + ": does not mock " + name);
            }
        }
        return violations;
    }

    static List<String> conventionViolations(String directory, String environment, Map<String, String> tf) {
        List<String> violations = new ArrayList<>();
        int conventions = 0;
        for (String text : tf.values()) {
            for (Block module : blocksOfType(text, "module")) {
                if (module.labels().equals(List.of("conventions"))) {
                    conventions++;
                    Map<String, String> arguments = attributes(module.body());
                    if (!"../../modules/conventions".equals(unquoted(arguments.getOrDefault("source", "")))
                            || !environment.equals(unquoted(arguments.getOrDefault("environment", "")))) {
                        violations.add(directory + ": the conventions module is not called as " + environment
                                + ": " + arguments);
                    }
                }
            }
            for (Block provider : blocksOfType(text, "provider")) {
                // Tags and an account are what the AWS provider has; the Kafka provider has neither.
                if (!provider.labels().equals(List.of("aws"))) {
                    continue;
                }
                boolean tagged = blocks(provider.body()).stream()
                        .filter(block -> block.type().equals("default_tags"))
                        .anyMatch(block -> "module.conventions.tags".equals(attributes(block.body()).get("tags")));
                if (!tagged) {
                    violations.add(directory + ": provider " + provider.labels()
                            + " does not take default_tags from module.conventions.tags");
                }
                if (!"[var.account_id]".equals(attributes(provider.body()).get("allowed_account_ids"))) {
                    violations.add(directory + ": provider " + provider.labels()
                            + " is not confined to its own account (allowed_account_ids = [var.account_id])");
                }
            }
        }
        if (conventions != 1) {
            violations.add(directory + ": the conventions module is called " + conventions + " times, not once");
        }
        return violations;
    }

    static List<String> destroyViolations(String path, String text) {
        List<String> violations = new ArrayList<>();
        for (Block resource : blocksOfType(text, "resource")) {
            if (!GUARDED.contains(resource.labels().get(0))) {
                continue;
            }
            boolean prevented = blocks(resource.body()).stream()
                    .filter(block -> block.type().equals("lifecycle"))
                    .anyMatch(block -> "true".equals(attributes(block.body()).get("prevent_destroy")));
            if (!prevented) {
                violations.add(path + ": resource " + String.join(".", resource.labels())
                        + " does not declare lifecycle { prevent_destroy = true }");
            }
        }
        return violations;
    }

    static List<String> secretViolations(String path, String text) {
        List<String> violations = new ArrayList<>();
        for (Block block : blocks(text)) {
            if (!block.type().equals("resource") && !block.type().equals("data")) {
                continue;
            }
            String kind = block.type() + " " + block.labels().get(0);
            if (SECRET_IN_STATE.contains(kind)) {
                violations.add(path + ": " + kind + " writes a secret value into state");
            }
            if (block.type().equals("resource") && DATABASES.contains(block.labels().get(0))) {
                Map<String, String> arguments = attributes(block.body());
                if (arguments.containsKey("password") || arguments.containsKey("master_password")) {
                    violations.add(path + ": " + String.join(".", block.labels())
                            + " is given its password, so state holds it (manage_master_user_password)");
                }
            }
        }
        return violations;
    }

    // --- the tree -------------------------------------------------------------------------------------------------

    private static Set<String> roots() {
        try (Stream<Path> list = Files.list(root.resolve(ENVIRONMENTS))) {
            return new TreeSet<>(list.filter(Files::isDirectory).map(path -> path.getFileName().toString()).toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every directory holding a .tf file: the roots and the modules. */
    private static Set<String> directories() {
        Set<String> directories = new TreeSet<>();
        files.keySet().stream()
                .filter(path -> path.endsWith(".tf"))
                .forEach(path -> directories.add(path.substring(0, path.lastIndexOf('/'))));
        return directories;
    }

    private static Map<String, String> terraformFiles(String directory) {
        Map<String, String> tf = new TreeMap<>();
        files.forEach((path, text) -> {
            if (path.endsWith(".tf") && path.substring(0, path.lastIndexOf('/')).equals(directory)) {
                tf.put(path, text);
            }
        });
        return tf;
    }

    /** Each required provider's local name and attributes ({@code source}, {@code version}). */
    private static Map<String, Map<String, String>> requiredProviderEntries(Map<String, String> tf) {
        Map<String, Map<String, String>> entries = new TreeMap<>();
        for (String text : tf.values()) {
            for (Block terraform : blocksOfType(text, "terraform")) {
                for (Block required : blocks(terraform.body())) {
                    if (required.type().equals("required_providers")) {
                        attributes(required.body()).forEach((name, value) -> {
                            String object = value.trim();
                            if (!object.startsWith("{") || !object.endsWith("}")) {
                                throw new IllegalStateException("provider " + name + " is not an object: " + value);
                            }
                            entries.put(name, attributes(object.substring(1, object.length() - 1)));
                        });
                    }
                }
            }
        }
        return entries;
    }

    /** Each required provider's source, mapped to its version constraint; keyed by local name in the map's order. */
    private static Map<String, String> requiredProviders(Map<String, String> tf) {
        Map<String, String> providers = new TreeMap<>();
        requiredProviderEntries(tf).forEach((name, attributes) -> providers.put(
                unquoted(attributes.getOrDefault("source", name)), unquoted(attributes.getOrDefault("version", ""))));
        return providers;
    }

    private static String address(String source) {
        return source.split("/").length == 2 ? "registry.terraform.io/" + source : source;
    }

    private static String pinned(String name) {
        Matcher pin = Pattern.compile("(?m)^" + name + "=(\\S+)\\s*$").matcher(EvidenceCitations.read(root.resolve(PINS)));
        assertThat(pin.find()).as("%s in %s", name, PINS).isTrue();
        return pin.group(1);
    }

    private static List<String> scriptLines() {
        List<String> lines = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        for (String line : EvidenceCitations.read(root.resolve(SCRIPT)).split("\\R", -1)) {
            String code = Pattern.compile("(^|\\s)#.*$").matcher(line).replaceAll("");
            // A command continued with a backslash is one command: read it as one line.
            if (code.endsWith("\\")) {
                joined.append(code, 0, code.length() - 1).append(' ');
                continue;
            }
            joined.append(code);
            lines.add(joined.toString());
            joined.setLength(0);
        }
        assertThat(lines).as("%s was read", SCRIPT).hasSizeGreaterThan(50);
        return lines;
    }

    private static List<String> workflowLines() {
        List<String> lines = new ArrayList<>();
        for (String line : EvidenceCitations.read(root.resolve(WORKFLOW)).split("\\R", -1)) {
            Matcher comment = Pattern.compile("(^|\\s)#").matcher(line);
            lines.add(comment.find() ? line.substring(0, comment.start()) : line);
        }
        return lines;
    }

    /** A job's lines: from its header, two spaces in under {@code jobs:}, to the next job or top-level key. */
    private static List<String> job(String name) {
        List<String> found = new ArrayList<>();
        boolean inJob = false;
        for (String line : workflowLines()) {
            Matcher header = JOB.matcher(line);
            if (header.matches()) {
                inJob = header.group(1).equals(name);
                continue;
            }
            if (inJob && !line.isBlank() && !line.startsWith(" ")) {
                inJob = false;
            }
            if (inJob) {
                found.add(line);
            }
        }
        assertThat(found).as("the workflow's %s job was read", name).isNotEmpty();
        return found;
    }

    // --- a small HCL reader ---------------------------------------------------------------------------------------

    /** A block: its type, its labels, and the text between its braces. */
    record Block(String type, List<String> labels, String body) {}

    private static List<Block> blocksOfType(String text, String type) {
        return blocks(text).stream().filter(block -> block.type().equals(type)).toList();
    }

    /** The blocks at the top level of {@code text}. Attributes are skipped; anything unreadable throws. */
    static List<Block> blocks(String text) {
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while ((i = skipSpaceAndComments(text, i)) < n) {
            int start = i;
            if (!Character.isLetter(text.charAt(i)) && text.charAt(i) != '_') {
                throw new IllegalStateException("unreadable HCL at: " + excerpt(text, i));
            }
            while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || "_-".indexOf(text.charAt(i)) >= 0)) {
                i++;
            }
            String word = text.substring(start, i);
            int next = skipInlineSpace(text, i);
            if (next < n && text.charAt(next) == '=' && (next + 1 >= n || text.charAt(next + 1) != '=')) {
                i = skipExpression(text, next + 1);
                continue;
            }
            List<String> labels = new ArrayList<>();
            i = next;
            while (i < n && text.charAt(i) != '{') {
                if (text.charAt(i) == '"') {
                    int end = skipString(text, i);
                    labels.add(text.substring(i + 1, end - 1));
                    i = end;
                } else if (Character.isLetter(text.charAt(i))) {
                    int labelStart = i;
                    while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                        i++;
                    }
                    labels.add(text.substring(labelStart, i));
                } else if (text.charAt(i) == ' ' || text.charAt(i) == '\t') {
                    i++;
                } else {
                    throw new IllegalStateException("unreadable block header at: " + excerpt(text, start));
                }
            }
            if (i >= n) {
                throw new IllegalStateException("a block header with no body at: " + excerpt(text, start));
            }
            int close = matching(text, i);
            blocks.add(new Block(word, List.copyOf(labels), text.substring(i + 1, close)));
            i = close + 1;
        }
        return blocks;
    }

    /** The attributes at the top level of {@code text}: each name and its expression's text. Blocks are skipped. */
    static Map<String, String> attributes(String text) {
        Map<String, String> attributes = new LinkedHashMap<>();
        int i = 0;
        int n = text.length();
        while ((i = skipSpaceAndComments(text, i)) < n) {
            if (text.charAt(i) == ',') {
                i++;
                continue;
            }
            int start = i;
            if (!Character.isLetter(text.charAt(i)) && text.charAt(i) != '_' && text.charAt(i) != '"') {
                throw new IllegalStateException("unreadable HCL at: " + excerpt(text, i));
            }
            String name;
            if (text.charAt(i) == '"') {
                int end = skipString(text, i);
                name = text.substring(i + 1, end - 1);
                i = end;
            } else {
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || "_-".indexOf(text.charAt(i)) >= 0)) {
                    i++;
                }
                name = text.substring(start, i);
            }
            int next = skipInlineSpace(text, i);
            if (next < n && text.charAt(next) == '=') {
                int end = skipExpression(text, next + 1);
                attributes.put(name, text.substring(next + 1, end).trim().replaceAll(",$", "").trim());
                i = end;
                continue;
            }
            // A nested block: skip its labels and body.
            i = next;
            while (i < n && text.charAt(i) != '{') {
                i = text.charAt(i) == '"' ? skipString(text, i) : i + 1;
            }
            if (i >= n) {
                throw new IllegalStateException("unreadable HCL at: " + excerpt(text, start));
            }
            i = matching(text, i) + 1;
        }
        return attributes;
    }

    /** A string literal's content, or the expression unchanged when it is not one. */
    static String unquoted(String expression) {
        String trimmed = expression.trim();
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : trimmed;
    }

    /** The end of an expression: a newline, comma or closing bracket at depth zero. */
    private static int skipExpression(String text, int i) {
        int n = text.length();
        int depth = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i);
                continue;
            }
            if (c == '<' && text.startsWith("<<", i)) {
                i = skipHeredoc(text, i);
                continue;
            }
            if (c == '#' || text.startsWith("//", i) || text.startsWith("/*", i)) {
                if (depth == 0 && !text.startsWith("/*", i)) {
                    return lineEnd(text, i);
                }
                i = skipSpaceAndComments(text, i);
                continue;
            }
            if ("([{".indexOf(c) >= 0) {
                depth++;
            } else if (")]}".indexOf(c) >= 0) {
                if (depth == 0) {
                    return i;
                }
                depth--;
            } else if ((c == '\n' || c == ',') && depth == 0) {
                return c == ',' ? i + 1 : i;
            }
            i++;
        }
        return i;
    }

    /** The index of the brace closing the one at {@code open}. */
    private static int matching(String text, int open) {
        int depth = 0;
        int i = open;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i);
                continue;
            }
            if (c == '<' && text.startsWith("<<", i)) {
                i = skipHeredoc(text, i);
                continue;
            }
            if (c == '#' || text.startsWith("//", i) || text.startsWith("/*", i)) {
                i = skipSpaceAndComments(text, i);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
            i++;
        }
        throw new IllegalStateException("an unclosed brace at: " + excerpt(text, open));
    }

    /** Past a string literal starting at {@code i}, its {@code ${...}} and {@code %{...}} templates included. */
    private static int skipString(String text, int i) {
        int n = text.length();
        i++;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            if ((c == '$' || c == '%') && i + 1 < n && text.charAt(i + 1) == '{') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    char t = text.charAt(i);
                    if (t == '"') {
                        i = skipString(text, i);
                        continue;
                    }
                    if (t == '{') {
                        depth++;
                    } else if (t == '}') {
                        depth--;
                    }
                    i++;
                }
                continue;
            }
            i++;
        }
        throw new IllegalStateException("an unterminated string at: " + excerpt(text, i));
    }

    /** Past a heredoc ({@code <<EOT} or {@code <<-EOT}) to the line after its terminator. */
    private static int skipHeredoc(String text, int i) {
        Matcher opener = Pattern.compile("<<-?([A-Za-z_][A-Za-z0-9_]*)").matcher(text);
        if (!opener.find(i) || opener.start() != i) {
            return i + 2;
        }
        Matcher terminator = Pattern.compile("(?m)^\\s*" + opener.group(1) + "\\s*$").matcher(text);
        if (!terminator.find(lineEnd(text, i))) {
            throw new IllegalStateException("an unterminated heredoc at: " + excerpt(text, i));
        }
        return terminator.end();
    }

    private static int skipSpaceAndComments(String text, int i) {
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#' || text.startsWith("//", i)) {
                i = lineEnd(text, i);
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new IllegalStateException("an unterminated comment at: " + excerpt(text, i));
                }
                i = end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    private static int skipInlineSpace(String text, int i) {
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static int lineEnd(String text, int i) {
        int end = text.indexOf('\n', i);
        return end < 0 ? text.length() : end;
    }

    private static String excerpt(String text, int i) {
        return text.substring(i, Math.min(text.length(), i + 60)).replace('\n', ' ');
    }
}
