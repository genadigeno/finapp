# The production root module (ADR-0121). It composes the concern modules as P15-TSK-014 to -016 add
# them; today it holds the conventions alone, and nothing here has ever been applied.

module "conventions" {
  source = "../../modules/conventions"

  environment = "production"
}
