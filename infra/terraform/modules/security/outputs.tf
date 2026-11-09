output "kms_key_arns" {
  description = "Each key's ARN, by purpose: what the roots hand the concern modules."
  value       = { for purpose, key in aws_kms_key.this : purpose => key.arn }
}

output "secret_entry_names" {
  description = "Every entry this module creates, by workload and variable. A plan whose inventory and manifests disagree stops here."
  value       = { for key, entry in local.entries : key => aws_secretsmanager_secret.entry[key].name }

  precondition {
    condition     = local.unparsed_rows == 0
    error_message = "A row of SECRET_MANAGEMENT.md sections 3.2 or 3.3 could not be read: the entries cannot be taken from a table this module does not understand."
  }

  precondition {
    condition     = length(local.rows_with_no_entry) == 0
    error_message = "An inventory row has no entry: ${join(", ", local.rows_with_no_entry)}."
  }

  precondition {
    condition     = length(local.entries_with_no_row) == 0
    error_message = "An entry has no inventory row: ${join(", ", local.entries_with_no_row)}."
  }

  precondition {
    condition     = length(local.entries_off_their_path) == 0
    error_message = "An entry is not under finapp/<environment>/<workload>/: ${join(", ", local.entries_off_their_path)}."
  }
}

output "secret_reader_role_arns" {
  description = "Each workload's reader role: what an environment puts on its ServiceAccount's eks.amazonaws.com/role-arn annotation."
  value       = { for workload, role in aws_iam_role.secret_reader : workload => role.arn }
}

output "reader_entry_arns" {
  description = "Exactly what each reader may read, from its planned policy."
  value       = { for workload, policy in aws_iam_role_policy.secret_reader : workload => jsondecode(policy.policy).Statement[0].Resource }
}

output "reader_trusted_subjects" {
  description = "The conditions each reader's trust names, from its planned role."
  value       = { for workload, role in aws_iam_role.secret_reader : workload => try(jsondecode(role.assume_role_policy).Statement[0].Condition.StringEquals, {}) }
}

output "access_entry_groups" {
  description = "The Kubernetes groups each access entry carries, from the plan."
  value       = { for name, entry in aws_eks_access_entry.human : name => entry.kubernetes_groups }
}

output "human_access_key_counts" {
  description = "How many access keys each person holds, read on every plan. Any number but zero has already stopped that plan."
  value       = { for name, keys in data.aws_iam_access_keys.human : name => length(keys.access_keys) }
}

output "human_role_arns" {
  description = "Each role a person assumes, by name."
  value       = { for name, role in aws_iam_role.human : name => role.arn }
}
