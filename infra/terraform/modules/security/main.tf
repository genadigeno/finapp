# Keys, secret entries, workload identities and human access (ADR-0121, ADR-0123, PHASE_15_PLAN.md
# section 7). Never applied: there is no account in Phase 15, so every property here is asserted by
# tests/security.tftest.hcl over a plan against a mock provider, which proves this configuration and nothing
# about what AWS accepts or enforces. README.md says which policies could not be checked without an account.
#
# Least privilege is written as literals, so that no environment can turn it off. NOTHING HERE LISTS A
# SECRET. The names come from the files that already hold them: docs/architecture/SECRET_MANAGEMENT.md
# sections 3.2 and 3.3 (the inventory), the two ExternalSecrets (what the operator asks for) and the
# overlay's SecretStores (the path each is read under). SecretInventoryIsCompleteTest holds the first two
# equal; the outputs' preconditions below refuse a plan where they differ.
#
# No value enters this module or its state: aws_secretsmanager_secret_version and every generator are
# refused by TerraformIsPinnedAndCheckedTest, and the first fill of each entry is OPERATIONS_RUNBOOK.md 11.7.

locals {
  environment = trimprefix(var.name_prefix, "finapp-")

  # --------------------------------------------------------------------------------------------------
  # What the inventory delivers, read from the document's two tables. A row that does not parse is counted
  # below and stops the plan, so a changed table cannot pass as a shorter one.
  # --------------------------------------------------------------------------------------------------
  inventory_text = file(var.secret_inventory_path)
  inventory_body = split("### 3.3 ", split("### 3.2 ", local.inventory_text)[1])[0]
  beside_body    = split("### 3.4 ", split("### 3.3 ", local.inventory_text)[1])[0]

  inventory_rows = [
    for cells in regexall("(?m)^\\| `([A-Z][A-Z0-9_]*)` \\|[^|]*\\| ([a-z-]+) \\|[^|]*\\|[^|]*\\| ([^|]*) \\| ([^|]*) \\|$", local.inventory_body) :
    { variable = cells[0], class = cells[1], consumer = cells[2], delivered = cells[3] }
  ]
  beside_rows = [
    for cells in regexall("(?m)^\\| `([A-Z][A-Z0-9_]*)` \\|[^|]*\\|[^|]*\\| ([^|]*) \\|$", local.beside_body) :
    { variable = cells[0], class = "beside-the-secrets", delivered = cells[1] }
  ]
  rows = concat(local.inventory_rows, local.beside_rows)

  # Every line of each table, its header and separator aside, must have parsed. Counted per table: summed, a section with
  # no table at all (minus two) could cancel two unread rows in the other.
  unparsed_inventory_rows = length(regexall("(?m)^\\|", local.inventory_body)) - 2 - length(local.inventory_rows)
  unparsed_beside_rows    = length(regexall("(?m)^\\|", local.beside_body)) - 2 - length(local.beside_rows)

  class_of    = { for row in local.inventory_rows : row.variable => row.class }
  consumer_of = { for row in local.inventory_rows : row.variable => row.consumer }

  # --------------------------------------------------------------------------------------------------
  # What the operator will read: each ExternalSecret's entries, each under its store's path.
  # --------------------------------------------------------------------------------------------------
  external_secrets = { for workload, path in var.external_secret_manifest_paths : workload => yamldecode(file(path)) }

  stores = {
    for document in [for text in split("\n---\n", file(var.secret_store_manifest_path)) : yamldecode(text)] :
    document.metadata.name => {
      prefix          = document.spec.provider.aws.prefix
      service_account = document.spec.provider.aws.auth.jwt.serviceAccountRef.name
    } if document.kind == "SecretStore"
  }

  delivered_entries = flatten([
    for workload, external_secret in local.external_secrets : [
      for item in external_secret.spec.data : {
        workload = workload
        variable = item.secretKey
        # Under this workload's own path, or through a store of its own for an entry another module creates.
        own = try(item.sourceRef.storeRef.name, external_secret.spec.secretStoreRef.name) == external_secret.spec.secretStoreRef.name
        name = format("%s%s",
          local.stores[try(item.sourceRef.storeRef.name, external_secret.spec.secretStoreRef.name)].prefix,
          item.remoteRef.key,
        )
      }
    ]
  ])

  # One entry per delivered row, under this workload's path. The Kafka pair reads the entry the managed brokers
  # dictate, which modules/kafka creates, so it is read below and never created here.
  entries = { for item in local.delivered_entries : "${item.workload}/${item.variable}" => item if item.own }

  # The compare of the two sources, both ways, stopped on at plan by the output below.
  inventory_targets = { for workload, external_secret in local.external_secrets : workload => external_secret.spec.target.name }

  rows_by_workload = {
    for workload, target in local.inventory_targets :
    workload => toset([for row in local.rows : row.variable if strcontains(row.delivered, "`${target}`")])
  }

  items_by_workload = {
    for workload in keys(local.external_secrets) :
    workload => toset([for item in local.delivered_entries : item.variable if item.workload == workload])
  }

  rows_with_no_entry  = flatten([for workload, variables in local.rows_by_workload : [for variable in setsubtract(variables, local.items_by_workload[workload]) : "${workload}/${variable}"]])
  entries_with_no_row = flatten([for workload, variables in local.items_by_workload : [for variable in setsubtract(variables, local.rows_by_workload[workload]) : "${workload}/${variable}"]])

  entries_off_their_path = [
    for key, entry in local.entries : key if !startswith(entry.name, "finapp/${local.environment}/${entry.workload}/")
  ]

  # The one entry anything may read from outside its own path, by the name the managed brokers dictate.
  entries_read_elsewhere_off_their_name = distinct([
    for item in local.delivered_entries : "${item.workload}/${item.variable}" if !item.own && item.name != "AmazonMSK_${var.name_prefix}-application"
  ])

  # Everything each workload's reader may read: its own entries, and what its ExternalSecret reads elsewhere. The application's SCRAM
  # entry comes with the one its rotation adds (P15-TSK-020, add-roll-remove: modules/kafka's alternate user): an overlay points the
  # two entries at it for the roll, and a reader that could not read it would leave the sync failing and the old credential in place.
  reads = {
    for workload in keys(local.external_secrets) :
    workload => toset(flatten([
      for item in local.delivered_entries : item.name == "AmazonMSK_${var.name_prefix}-application" ? [item.name, "${item.name}-alternate"] : [item.name]
      if item.workload == workload
    ]))
  }

  # A Secrets Manager ARN ends in a hyphen and six random characters. One entry each, known at plan, and no prefix
  # that would admit whatever is created under it later.
  entry_arn = "arn:aws:secretsmanager:${var.region}:${var.account_id}:secret:%s-??????"

  # The field-level keys are wrapped by a key of their own (ADR-0123 decision 1).
  key_purpose_of = { for key, entry in local.entries : key => lookup(local.class_of, entry.variable, "") == "field-encryption-key" ? "field_keys" : "secrets" }

  # A field key's entry is the only copy of that key, so a renamed row must not schedule its deletion. Every other
  # entry can be filled again, and guarding those too would make removing a provider a state surgery.
  field_key_entries = { for key, entry in local.entries : key => entry if local.key_purpose_of[key] == "field_keys" }
  other_entries     = { for key, entry in local.entries : key => entry if local.key_purpose_of[key] != "field_keys" }

  reader_key_purposes = {
    for workload in keys(local.external_secrets) :
    workload => toset(concat(
      [for key, purpose in local.key_purpose_of : purpose if local.entries[key].workload == workload],
      # An entry read elsewhere is under the secrets key too: modules/kafka is given that key for it.
      [for item in local.delivered_entries : "secrets" if item.workload == workload && !item.own],
    ))
  }

  decrypt_through_secrets_manager = {
    StringEquals = { "kms:ViaService" = "secretsmanager.${var.region}.amazonaws.com" }
  }

  account_root = "arn:aws:iam::${var.account_id}:root"
  cluster_arn  = "arn:aws:eks:${var.region}:${var.account_id}:cluster/${var.cluster_name}"
  issuer_host  = trimprefix(var.workload_identity_issuer, "https://")

  # --------------------------------------------------------------------------------------------------
  # Keys, per purpose. The account holds administration and nothing that uses a key, so a key is used only through
  # a statement of its own. The account keeps PutKeyPolicy, so a wrong policy can be repaired and no key is locked.
  # --------------------------------------------------------------------------------------------------
  key_administration = [
    "kms:CancelKeyDeletion",
    "kms:CreateAlias",
    "kms:DeleteAlias",
    "kms:DescribeKey",
    "kms:DisableKey",
    "kms:DisableKeyRotation",
    "kms:EnableKey",
    "kms:EnableKeyRotation",
    "kms:GetKeyPolicy",
    "kms:GetKeyRotationStatus",
    "kms:ListAliases",
    "kms:ListGrants",
    "kms:ListKeyPolicies",
    "kms:ListKeyRotations",
    "kms:ListResourceTags",
    "kms:PutKeyPolicy",
    "kms:RevokeGrant",
    "kms:RotateKeyOnDemand",
    "kms:ScheduleKeyDeletion",
    "kms:TagResource",
    "kms:UntagResource",
    "kms:UpdateAlias",
    "kms:UpdateKeyDescription",
  ]

  key_use_through_a_service = [
    "kms:CreateGrant",
    "kms:Decrypt",
    "kms:DescribeKey",
    "kms:Encrypt",
    "kms:GenerateDataKey",
    "kms:GenerateDataKeyWithoutPlaintext",
    "kms:ListGrants",
    "kms:ReEncryptFrom",
    "kms:ReEncryptTo",
  ]

  # Each purpose's services, by the name kms:ViaService carries. RDS, MSK, S3, AWS Backup and Secrets Manager use a
  # key as their caller, so the statement admits this account's principals through that service alone.
  key_purposes = {
    etcd            = { description = "Kubernetes Secrets in etcd", services = [] }
    database        = { description = "the database's volume, its automated backups and its snapshots", services = ["rds"] }
    database_backup = { description = "the database's backup vault", services = ["backup"] }
    secrets         = { description = "the secret entries, the SCRAM entries and the master credential", services = ["kafka", "secretsmanager"] }
    field_keys      = { description = "the field-level encryption keys' entries", services = ["secretsmanager"] }
    brokers         = { description = "the brokers' volumes", services = ["kafka"] }
    state           = { description = "the Terraform state bucket", services = ["s3"] }
    archive         = { description = "the audit and log archive buckets", services = ["s3"] }
    logs            = { description = "the CloudWatch log groups and the telemetry buckets", services = ["s3"] }
  }

  # Only a copy crossing an account needs the source key shared with its destination, and then only through the services
  # that make the copy, in this region and the vault's.
  backup_copy_account = split(":", var.database_backup_copy_vault_arn)[4]
  backup_copy_services = flatten([
    for region in distinct([var.region, split(":", var.database_backup_copy_vault_arn)[3]]) : ["backup.${region}.amazonaws.com", "rds.${region}.amazonaws.com"]
  ])

  key_policies = {
    for purpose, key in local.key_purposes : purpose => {
      Version = "2012-10-17"
      Statement = concat(
        [{
          Sid       = "TheAccountAdministersTheKeyAndNeverUsesIt"
          Effect    = "Allow"
          Principal = { AWS = local.account_root }
          Action    = local.key_administration
          Resource  = "*"
        }],
        length(key.services) > 0 ? [{
          Sid       = "UseThroughItsServiceAlone"
          Effect    = "Allow"
          Principal = { AWS = local.account_root }
          Action    = local.key_use_through_a_service
          Resource  = "*"
          Condition = { StringEquals = { "kms:ViaService" = [for service in key.services : "${service}.${var.region}.amazonaws.com"] } }
        }] : [],
        # EKS needs the caller of CreateCluster to describe the key and make the grant. Neither kms:ViaService nor
        # kms:GrantIsForAWSResource can bound it: AWS lists no ViaService name for EKS, and documents that the
        # second is not supported for CreateCluster. So this is the account's, in IAM's hands, and these two actions alone.
        purpose == "etcd" ? [{
          Sid       = "TheCallerOfCreateClusterDescribesAndGrants"
          Effect    = "Allow"
          Principal = { AWS = local.account_root }
          Action    = ["kms:CreateGrant", "kms:DescribeKey"]
          Resource  = "*"
        }] : [],
        purpose == "etcd" ? [{
          Sid       = "TheClusterRoleEncryptsEtcd"
          Effect    = "Allow"
          Principal = { AWS = var.cluster_role_arn }
          Action    = ["kms:CreateGrant", "kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:ListGrants"]
          Resource  = "*"
        }] : [],
        purpose == "logs" ? [{
          Sid       = "CloudWatchLogsThisAccountsGroups"
          Effect    = "Allow"
          Principal = { Service = "logs.${var.region}.amazonaws.com" }
          Action    = ["kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey", "kms:GenerateDataKeyWithoutPlaintext", "kms:ReEncryptFrom", "kms:ReEncryptTo"]
          Resource  = "*"
          Condition = {
            ArnLike = {
              "kms:EncryptionContext:aws:logs:arn" = [
                # The cluster's name is the name prefix (modules/kubernetes). Written from the prefix and not from
                # cluster_name, because the cluster's own output would make this policy wait for the cluster it encrypts.
                "arn:aws:logs:${var.region}:${var.account_id}:log-group:/aws/eks/${var.name_prefix}/cluster",
                "arn:aws:logs:${var.region}:${var.account_id}:log-group:/finapp/${var.name_prefix}/*",
              ]
            }
          }
        }] : [],
        purpose == "database" && local.backup_copy_account != var.account_id ? [{
          Sid       = "TheBackupCopysAccountUsesItThroughTheCopy"
          Effect    = "Allow"
          Principal = { AWS = "arn:aws:iam::${local.backup_copy_account}:root" }
          Action    = ["kms:Decrypt", "kms:DescribeKey", "kms:GenerateDataKey", "kms:ReEncryptFrom", "kms:ReEncryptTo"]
          Resource  = "*"
          Condition = { StringEquals = { "kms:ViaService" = local.backup_copy_services } }
        }] : [],
        purpose == "database" && local.backup_copy_account != var.account_id ? [{
          Sid       = "TheBackupCopysAccountGrantsOnlyToAnAwsService"
          Effect    = "Allow"
          Principal = { AWS = "arn:aws:iam::${local.backup_copy_account}:root" }
          Action    = ["kms:CreateGrant"]
          Resource  = "*"
          Condition = { Bool = { "kms:GrantIsForAWSResource" = "true" } }
        }] : [],
      )
    }
  }

  # --------------------------------------------------------------------------------------------------
  # People. Workforce federation waits for a workforce identity provider (C13); until then a person is an IAM user
  # in one group, whose only standing rights are enrolling a factor and assuming the roles below with it.
  # --------------------------------------------------------------------------------------------------
  roles_on_the_cluster = {
    # The session is what bounds break-glass in time: Kubernetes RBAC has no expiring binding. One hour is the least
    # a role can be given, and OPERATIONS_RUNBOOK.md 11.25 holds the incident to it.
    "break-glass" = { kubernetes_group = "finapp-break-glass", session_seconds = 3600 }
    "read-only"   = { kubernetes_group = "finapp-read-only", session_seconds = 14400 }
  }

  # The role for the one-time bootstrap step exists only while someone has asked for it, and is never on the cluster.
  human_roles = merge(
    local.roles_on_the_cluster,
    { for name, role in { bootstrap = { kubernetes_group = "", session_seconds = 3600 } } : name => role if var.bootstrap_role_enabled },
  )

  # What a person may do in the console before a factor is on the session, and nothing else. The first list is scoped to the
  # person's own user and device, so a device must be named after its user; ListVirtualMFADevices takes no resource.
  # A person has no access key, so no shell session: signing in to the console is the only way a factor reaches a
  # session, and from there a person can assume a role but has no kubectl and no route to the private endpoint
  # (THREAT_MODEL.md section 6 item 31).
  mfa_enrolment_on_oneself = [
    "iam:ChangePassword",
    "iam:CreateVirtualMFADevice",
    "iam:EnableMFADevice",
    "iam:GetUser",
    "iam:ListMFADevices",
    "iam:ResyncMFADevice",
  ]
  mfa_enrolment = concat(local.mfa_enrolment_on_oneself, ["iam:ListVirtualMFADevices"])

  describe_the_cluster = {
    Sid      = "DescribeTheCluster"
    Effect   = "Allow"
    Action   = ["eks:DescribeCluster"]
    Resource = [local.cluster_arn]
  }

  # The only values a person can read, and only through the bootstrap role while it exists: the master credential, and the
  # passwords the step sets, which are the rows the inventory names it as a consumer of (SECRET_MANAGEMENT.md 3.2,
  # DATA_MIGRATIONS.md 2.2).
  bootstrap_step_entries = sort(distinct([
    for key, entry in local.entries : entry.name if strcontains(lookup(local.consumer_of, entry.variable, ""), "bootstrap-step")
  ]))

  read_what_the_bootstrap_step_reads = [
    {
      Sid      = "ReadWhatTheBootstrapStepReads"
      Effect   = "Allow"
      Action   = ["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue"]
      Resource = concat([var.master_user_secret_arn], [for name in local.bootstrap_step_entries : format(local.entry_arn, name)])
    },
    {
      Sid       = "DecryptWhatTheBootstrapStepReads"
      Effect    = "Allow"
      Action    = ["kms:Decrypt"]
      Resource  = [aws_kms_key.this["secrets"].arn]
      Condition = local.decrypt_through_secrets_manager
    },
  ]
}

# ------------------------------------------------------------------------------------------------
# Keys.
# ------------------------------------------------------------------------------------------------

resource "aws_kms_key" "this" {
  for_each = local.key_purposes

  description                        = "finapp ${local.environment}: ${each.value.description}."
  enable_key_rotation                = true
  deletion_window_in_days            = 30
  multi_region                       = false
  bypass_policy_lockout_safety_check = false
  policy                             = jsonencode(local.key_policies[each.key])

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_kms_alias" "this" {
  for_each = local.key_purposes

  name          = "alias/${var.name_prefix}-${replace(each.key, "_", "-")}"
  target_key_id = aws_kms_key.this[each.key].key_id
}

# ------------------------------------------------------------------------------------------------
# Secret entries, without values. Not scheduled for rotation: a rotation is a deliberate act the fleet follows
# (SECRET_MANAGEMENT.md 5.2), and the one rotation a service manages, the master credential's, is RDS's own.
# ------------------------------------------------------------------------------------------------

resource "aws_secretsmanager_secret" "entry" {
  for_each = local.other_entries

  name                    = each.value.name
  description             = "${each.value.variable} of finapp-${each.value.workload} in ${local.environment}: docs/architecture/SECRET_MANAGEMENT.md section 3."
  kms_key_id              = aws_kms_key.this[local.key_purpose_of[each.key]].arn
  recovery_window_in_days = 30
}

resource "aws_secretsmanager_secret" "field_key" {
  for_each = local.field_key_entries

  name                    = each.value.name
  description             = "${each.value.variable} of finapp-${each.value.workload} in ${local.environment}: docs/architecture/SECRET_MANAGEMENT.md section 3."
  kms_key_id              = aws_kms_key.this["field_keys"].arn
  recovery_window_in_days = 30

  lifecycle {
    prevent_destroy = true
  }
}

# ------------------------------------------------------------------------------------------------
# Workload identity: one role per workload, trusted by that workload's reader ServiceAccount alone. The readers run
# no pod (the operator exchanges a token for them), so no pod is given a cloud identity.
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "secret_reader" {
  for_each = local.external_secrets

  name                 = "${var.name_prefix}-${each.key}-secret-reader"
  max_session_duration = 3600

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRoleWithWebIdentity"
      Principal = { Federated = var.workload_identity_provider_arn }
      Condition = {
        StringEquals = {
          "${local.issuer_host}:aud" = "sts.amazonaws.com"
          "${local.issuer_host}:sub" = "system:serviceaccount:${var.workload_namespace}:${local.stores[each.value.spec.secretStoreRef.name].service_account}"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "secret_reader" {
  for_each = local.external_secrets

  name = "read-its-own-entries"
  role = aws_iam_role.secret_reader[each.key].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadItsOwnEntries"
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [for name in sort(tolist(local.reads[each.key])) : format(local.entry_arn, name)]
      },
      {
        Sid       = "DecryptThroughSecretsManager"
        Effect    = "Allow"
        Action    = ["kms:Decrypt"]
        Resource  = [for purpose in sort(tolist(local.reader_key_purposes[each.key])) : aws_kms_key.this[purpose].arn]
        Condition = local.decrypt_through_secrets_manager
      },
    ]
  })
}

# ------------------------------------------------------------------------------------------------
# People.
# ------------------------------------------------------------------------------------------------

resource "aws_iam_group" "humans" {
  name = "${var.name_prefix}-humans"
  path = "/humans/"
}

resource "aws_iam_group_policy" "humans" {
  name  = "require-a-factor-and-refuse-long-lived-credentials"
  group = aws_iam_group.humans.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EnrolAFactor"
        Effect = "Allow"
        Action = local.mfa_enrolment_on_oneself
        Resource = [
          "arn:aws:iam::${var.account_id}:mfa/$${aws:username}",
          "arn:aws:iam::${var.account_id}:user/humans/$${aws:username}",
        ]
      },
      {
        Sid      = "ListTheDevicesToEnrolOne"
        Effect   = "Allow"
        Action   = ["iam:ListVirtualMFADevices"]
        Resource = "*"
      },
      {
        Sid       = "RefuseEverythingWithoutAFactor"
        Effect    = "Deny"
        NotAction = local.mfa_enrolment
        Resource  = "*"
        Condition = { BoolIfExists = { "aws:MultiFactorAuthPresent" = "false" } }
      },
      {
        Sid      = "RefuseLongLivedCredentials"
        Effect   = "Deny"
        Action   = ["iam:CreateAccessKey", "iam:CreateServiceSpecificCredential", "iam:UpdateAccessKey", "iam:UploadSSHPublicKey", "iam:UploadSigningCertificate"]
        Resource = "*"
      },
    ]
  })
}

resource "aws_iam_user" "human" {
  for_each = var.human_users

  name = each.key
  path = "/humans/"
  # A person's password and device are made by hand, so without this their removal fails at apply, DeleteUser refusing
  # while either exists, and the group and trust updates that depend on it are skipped: they would keep break-glass.
  force_destroy = true
}

resource "aws_iam_group_membership" "humans" {
  name  = "${var.name_prefix}-humans"
  group = aws_iam_group.humans.name
  users = [for name in sort(keys(var.human_users)) : aws_iam_user.human[name].name]
}

# Terraform creates no access key and the group refuses one. This reads what exists, so that a key made some other
# way stops the next plan rather than going unnoticed.
data "aws_iam_access_keys" "human" {
  for_each = var.human_users

  user = aws_iam_user.human[each.key].name

  lifecycle {
    postcondition {
      condition     = length(self.access_keys) == 0
      error_message = "A person holds a long-lived access key. Delete it: a human reaches this account through a role, with a factor."
    }
  }
}

# What any human role on the cluster can ever do, whatever is later attached to it.
resource "aws_iam_policy" "human_boundary" {
  name        = "${var.name_prefix}-human-boundary"
  description = "The most a human role on the cluster can do in this account."

  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [local.describe_the_cluster]
  })
}

# And the most the bootstrap role can do, for as long as it exists.
resource "aws_iam_policy" "bootstrap_boundary" {
  count = var.bootstrap_role_enabled ? 1 : 0

  name        = "${var.name_prefix}-human-bootstrap-boundary"
  description = "The most the one-time bootstrap role can do in this account."

  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = local.read_what_the_bootstrap_step_reads
  })
}

resource "aws_iam_role" "human" {
  for_each = local.human_roles

  name                 = "${var.name_prefix}-human-${each.key}"
  max_session_duration = each.value.session_seconds
  permissions_boundary = each.key == "bootstrap" ? aws_iam_policy.bootstrap_boundary[0].arn : aws_iam_policy.human_boundary.arn

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { AWS = [for name in sort(keys(var.human_users)) : aws_iam_user.human[name].arn if contains(var.human_users[name].roles, each.key)] }
      Condition = {
        Bool            = { "aws:MultiFactorAuthPresent" = "true" }
        NumericLessThan = { "aws:MultiFactorAuthAge" = tostring(each.value.session_seconds) }
        # So that the cluster's audit log and the cloud's name the person, not the role.
        StringEquals = { "sts:RoleSessionName" = "$${aws:username}" }
      }
    }]
  })
}

resource "aws_iam_role_policy" "human" {
  for_each = local.human_roles

  name = "what-this-role-may-do"
  role = aws_iam_role.human[each.key].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [for statement in [local.describe_the_cluster] : statement if each.key != "bootstrap"],
      [for statement in local.read_what_the_bootstrap_step_reads : statement if each.key == "bootstrap"],
    )
  })
}

# ------------------------------------------------------------------------------------------------
# The cluster's API. Its authentication mode is API and its creator holds nothing (modules/kubernetes), so these are
# the only way in. They carry a group and no access policy: TerraformIsPinnedAndCheckedTest refuses an association, because an
# access policy grants what the manifests' RBAC cannot see.
# ------------------------------------------------------------------------------------------------

resource "aws_eks_access_entry" "human" {
  for_each = local.roles_on_the_cluster

  cluster_name      = var.cluster_name
  principal_arn     = aws_iam_role.human[each.key].arn
  kubernetes_groups = [each.value.kubernetes_group]
  type              = "STANDARD"
}
