# tflint's configuration for every root and module under infra/terraform (P15-TSK-013, ADR-0121).
#
# Only the ruleset bundled in the pinned tflint image (TFLINT_* in infra/scanner-pins.sh) is used:
# no plugin is downloaded at run time, so CI and a laptop lint with the same bytes. A cloud ruleset
# (tflint-ruleset-aws) is a plugin fetched by `tflint --init`, and trivy config already reads the AWS
# properties, so none is added.
#
# Every rule of the bundled ruleset is on (preset "all"). The conventions it holds:
#   terraform_required_version, terraform_required_providers   a root or module without its pins
#   terraform_naming_convention                                 an identifier that is not snake_case
#   terraform_standard_module_structure                         main.tf, variables.tf, outputs.tf
#   terraform_typed_variables, terraform_documented_variables,
#   terraform_documented_outputs                                every input and output typed and described
# infra/scripts/terraform-check.sh plants a defect for two of them on every run, and requires tflint to
# refuse each by name: terraform_required_providers (the AWS pin dropped) and
# terraform_naming_convention (a module called namingConventions).

config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "all"
}

rule "terraform_naming_convention" {
  enabled = true
  format  = "snake_case"
}
