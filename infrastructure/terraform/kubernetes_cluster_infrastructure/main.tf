data "digitalocean_domain" "main" {
  name = var.domain_name
}

data "digitalocean_kubernetes_cluster" "foreign_language_reader" {
  name = var.cluster_name
}

# Configure database

resource "digitalocean_database_cluster" "api_mysql" {
  name       = "foreign-language-reader"
  engine     = "mysql"
  version    = "8"
  size       = "db-s-1vcpu-1gb"
  region     = "sfo2"
  node_count = 1
}

resource "digitalocean_database_firewall" "allow_kubernetes" {
  cluster_id = digitalocean_database_cluster.api_mysql.id

  rule {
    type  = "k8s"
    value = data.digitalocean_kubernetes_cluster.foreign_language_reader.id
  }
}

resource "digitalocean_database_user" "api_user" {
  cluster_id = digitalocean_database_cluster.api_mysql.id
  name       = "api"
}

resource "digitalocean_database_db" "api_database" {
  cluster_id = digitalocean_database_cluster.api_mysql.id
  name       = "foreign-language-reader"
}

resource "kubernetes_secret" "api_database_credentials" {
  metadata {
    name = "api-database-credentials"
  }

  data = {
    username          = digitalocean_database_user.api_user.name
    password          = digitalocean_database_user.api_user.password
    host              = digitalocean_database_cluster.api_mysql.private_host
    port              = digitalocean_database_cluster.api_mysql.port
    database          = digitalocean_database_db.api_database.name
    connection_string = "ecto://${digitalocean_database_user.api_user.name}:${digitalocean_database_user.api_user.password}@${digitalocean_database_cluster.api_mysql.private_host}:${digitalocean_database_cluster.api_mysql.port}/${digitalocean_database_db.api_database.name}"
  }
}

# Cache for language service

resource "digitalocean_database_cluster" "language_service_cache" {
  name            = "foreign-language-reader-cache"
  engine          = "redis"
  eviction_policy = "allkeys_lru"
  version         = "5"
  size            = "db-s-1vcpu-1gb"
  region          = "sfo2"
  node_count      = 1
}

resource "digitalocean_database_firewall" "allow_kubernetes_to_redis" {
  cluster_id = digitalocean_database_cluster.language_service_cache.id

  rule {
    type  = "k8s"
    value = data.digitalocean_kubernetes_cluster.foreign_language_reader.id
  }
}

resource "kubernetes_secret" "language_service_cache_credentials" {
  metadata {
    name = "language-service-cache-credentials"
  }

  data = {
    username          = digitalocean_database_cluster.language_service_cache.user
    password          = digitalocean_database_cluster.language_service_cache.password
    host              = digitalocean_database_cluster.language_service_cache.private_host
    port              = digitalocean_database_cluster.language_service_cache.port
    connection_string = "rediss://${digitalocean_database_cluster.language_service_cache.user}:${digitalocean_database_cluster.language_service_cache.password}@${digitalocean_database_cluster.language_service_cache.private_host}:${digitalocean_database_cluster.language_service_cache.port}/0"
  }
}

# Configure networking

# This is created by K8s and needs to be imported manually
# Honestly should probably let K8s manage it and just use it informationally
# Note that the ports are randomly assigned so you should update these to match what you import
# data "digitalocean_loadbalancer" "foreign_language_reader" {
#   name = "a9df278143ceb40a0814972ad52c9651"
# }
#
# resource "digitalocean_record" "api" {
#   domain = var.domain_name
#   type   = "A"
#   name   = "api"
#   value  = data.digitalocean_loadbalancer.foreign_language_reader.ip
# }
#
# resource "digitalocean_record" "language" {
#   domain = var.domain_name
#   type   = "A"
#   name   = "language"
#   value  = data.digitalocean_loadbalancer.foreign_language_reader.ip
# }
