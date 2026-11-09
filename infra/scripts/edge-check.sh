#!/bin/sh
# The edge's check (P15-TSK-011, ADR-0120 decision 6, THREAT_MODEL.md T1, T3, T8, T20, T21, T31, T40).
#
# TWO HALVES. First the manifests, as infra/scripts/manifest-check.sh does for the application: every overlay's application stream and edge
# stream rendered together, kubeconform -strict, the policies of manifest-policy/edge.rego (which re-read finapp.rego's hardening rules
# for the controllers), and then every policy again over a plant it must refuse, for the reason it must give. EdgeManifestsTest
# requires the plants to cover every rule id in edge.rego.
#
# THEN THE ROUTING, THROUGH THE REAL PROXY. The route files are matched by Traefik, not by anything in this repository, and a path allow-list is
# only as good as what the proxy does with `//`, `..`, `%2F` and `;`. So this script runs the pinned image, with the committed files
# unchanged, against a stand-in backend that reports each request it receives, and asks it: which spellings of an operator path reach
# the backend, whose X-Forwarded-For arrives, what is refused and with what status, and whether a body at the application's limit gets through.
# The stand-in is Traefik's own whoami image, under the name the route files address, so not a byte of the route file changes.
#
# WHAT IT NEVER DOES. It talks to no cluster and holds no credential. The certificate is generated per run and thrown away. Nothing here can
# change the tree: the repository is mounted read-only, and the scratch volume and the containers are this run's and removed at exit.
#
# THE kind HALF - a real API server, real NetworkPolicy, real cert-manager, a real load balancer's source address - is P15-TSK-012's. This script
# proves the proxy's matching and the controllers' manifests, and nothing about a cluster.
#
# Exit status: 0 every check green and every plant refused; 1 otherwise; 2 no Docker or no openssl.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$DIR/.." && pwd)

OVERLAYS="kind staging production"

# Git Bash on Windows otherwise rewrites /repo, /scratch and /v1/... into host paths. Unset and harmless on Linux, where CI runs.
export MSYS_NO_PATHCONV=1

# ---------------------------------------------------------------------------------------------------
# Inside the Kustomize container: /repo/deploy read-only, /scratch this run's volume.
# ---------------------------------------------------------------------------------------------------
PLANTS=/scratch/edge-plants

render_both() {
    # render_both <deploy dir> <overlay> <output file>
    kustomize build "$1/overlays/$2" > "$3"
    printf '%s\n' '---' >> "$3"
    kustomize build "$1/edge/overlays/$2" >> "$3"
}

edge_plant() {
    # edge_plant <name> [overlay]: a fresh copy of deploy/, rendered through <overlay> (production unless named).
    rm -rf "${PLANTS:?}/$1"
    mkdir -p "$PLANTS/$1"
    cp -R /repo/deploy "$PLANTS/$1/deploy"
    printf 'overlay: %s\n' "${2:-production}" > "$PLANTS/$1/overlay.yaml"
    printf '%s' "${2:-production}" > "$PLANTS/$1/overlay"
}

edge_mutate() {
    # edge_mutate <plant> <file under deploy/> <sed program>: fails if the mutation no longer applies.
    sed -i "$3" "$PLANTS/$1/deploy/$2"
    if cmp -s "/repo/deploy/$2" "$PLANTS/$1/deploy/$2"; then
        echo "!!   plant $1 was not planted: $2 is unchanged, so its mutation no longer applies" >&2
        exit 1
    fi
}

edge_plant_on() {
    edge_plant "$1" "${4:-}"
    edge_mutate "$1" "$2" "$3"
}

# Kubernetes' own field, named once: this line's shape is not read as a credential by CommittedConfigurationHoldsNoSecretTest,
# and a sed program saying it literally would be.
SWITCH=automountServiceAccountToken
STATIC=edge/base/traefik.yaml
CONTROLLERS=edge/base/controllers.yaml
PUBLIC_ROUTES=edge/base/public-routes.yaml
OPERATOR_ROUTES=edge/base/operator-routes.yaml
PUBLIC_EDGE=edge/base/public-edge.yaml
PRODUCTION_EDGE=edge/overlays/production/kustomization.yaml
PRODUCTION_APP=overlays/production/kustomization.yaml
KUST=edge/base/kustomization.yaml

plant_all() {
    # The general hardening, reused from finapp.rego, on a controller.
    edge_plant_on edge-mounted-account "$CONTROLLERS" "1,/$SWITCH: false/s/$SWITCH: false/$SWITCH: true/"
    edge_plant_on edge-image-tag "$CONTROLLERS" '1,/traefik@sha256:/s/traefik@sha256:[0-9a-f]*/traefik:v3/'
    edge_plant_on edge-root "$CONTROLLERS" '1,/runAsNonRoot: true/s/runAsNonRoot: true/runAsNonRoot: false/'
    edge_plant_on edge-writable "$CONTROLLERS" '1,/readOnlyRootFilesystem: true/s/readOnlyRootFilesystem: true/readOnlyRootFilesystem: false/'

    edge_plant_on edge-one-replica "$CONTROLLERS" '1,/replicas: 2/s/replicas: 2/replicas: 1/'
    edge_plant_on edge-no-routes "$KUST" '/routes.yaml=public-routes.yaml/d'
    edge_plant_on edge-no-role-label "$CONTROLLERS" '1,/finapp\/role: ingress-controller/s/finapp\/role: ingress-controller/finapp\/role: other/'

    edge_plant_on edge-forwarded-insecure "$STATIC" 's/insecure: false/insecure: true/'
    edge_plant_on edge-operator-forwarded edge/base/traefik-operator.yaml 's/insecure: false/insecure: true/'
    edge_plant_on edge-forwarded-trusted "$STATIC" 's/insecure: false/insecure: false\n      trustedIPs: ["10.0.0.0\/8"]/'
    edge_plant_on edge-alias-keep "$STATIC" 's/aliasHeadersStrategy: delete/aliasHeadersStrategy: keep/'
    edge_plant_on edge-encoded-slash "$STATIC" 's/allowEncodedSlash: false/allowEncodedSlash: true/'
    edge_plant_on edge-encoded-absent "$STATIC" '/allowEncodedPercent: false/d'
    edge_plant_on edge-sanitize "$STATIC" 's/sanitizePath: true/sanitizePath: false/'
    edge_plant_on edge-plain-listener "$STATIC" 's/^  management:$/  web:\n    address: ":8080"\n  management:/'
    edge_plant_on edge-tls-dropped "$STATIC" '/^      tls: {}$/d'
    edge_plant_on edge-kubernetes-provider "$STATIC" 's/^  file:$/  kubernetesCRD: {}\n  file:/'

    edge_plant_on edge-public-operator "$PUBLIC_ROUTES" 's/^  routers:$/  routers:\n    planted:\n      entryPoints: [websecure]\n      rule: "Method(`GET`) \&\& Path(`\/v1\/operator\/planted`)"\n      service: finapp-app\n      middlewares: [edge-headers, source-session, body-cap]\n      tls: {}/'
    edge_plant_on edge-public-no-limit "$PUBLIC_ROUTES" '1,/source-session, hold-session, body-cap/s/source-session, //'
    edge_plant_on edge-public-no-hold "$PUBLIC_ROUTES" '1,/hold-session, body-cap/s/hold-session, //'
    edge_plant_on edge-hold-on-host "$PUBLIC_EDGE" '/amount: 5$/{n;s/sourceCriterion: .*/sourceCriterion: {requestHost: true}/}'
    edge_plant_on edge-spool-on-disk "$CONTROLLERS" '1,/medium: Memory/{/medium: Memory/d}'
    edge_plant_on edge-spool-over-limit "$CONTROLLERS" 's/sizeLimit: 256Mi/sizeLimit: 400Mi/'
    edge_plant_on edge-no-backend-timeout "$PUBLIC_EDGE" '/responseHeaderTimeout: 60s/d'
    edge_plant_on edge-no-write-timeout "$STATIC" '/writeTimeout: 120s/d'
    edge_plant_on edge-upgrade-forwarded "$PUBLIC_EDGE" '/^          Upgrade: ""$/d'
    edge_plant_on edge-operator-no-allowlist "$OPERATOR_ROUTES" '1,/internal-only, body-cap/s/internal-only, //'

    edge_plant_on edge-sticky "$PUBLIC_EDGE" 's/^        passHostHeader: false$/        passHostHeader: false\n        sticky:\n          cookie: {}/'
    edge_plant_on edge-reuse "$PUBLIC_EDGE" 's/maxIdleConnsPerHost: -1/maxIdleConnsPerHost: 200/'

    edge_plant_on edge-management-port "$CONTROLLERS" '1,/targetPort: https/s/targetPort: https/targetPort: management/'
    edge_plant_on edge-operator-public "$PRODUCTION_EDGE" 's/aws-load-balancer-scheme: internal$/aws-load-balancer-scheme: internet-facing/'
    edge_plant_on edge-no-source-address edge/overlays/kind/kustomization.yaml '/name: finapp-edge-public/,/value: Local/{/op: add/{N;N;/externalTrafficPolicy/d}}' kind
    edge_plant_on edge-no-preserve "$PRODUCTION_EDGE" '1,/target-group-attributes/{/target-group-attributes/d}'
    edge_plant_on edge-nlb-instance "$PRODUCTION_EDGE" '1,/nlb-target-type: ip/s/nlb-target-type: ip/nlb-target-type: instance/'

    edge_plant edge-ingress
    cat > "$PLANTS/edge-ingress/deploy/edge/overlays/production/plain.yaml" <<'YAML'
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: planted
spec:
  rules:
    - http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: finapp-edge-public
                port:
                  number: 443
YAML
    edge_mutate edge-ingress "$PRODUCTION_EDGE" 's|^  - \.\./\.\./base$|  - ../../base\n  - plain.yaml|'

    edge_plant_on edge-no-default-deny "$KUST" '/  - networkpolicy.yaml/d'
    edge_plant_on edge-operator-internet edge/base/networkpolicy.yaml 's#cidr: 10.0.0.0/8#cidr: 0.0.0.0/0#'
    edge_plant_on edge-proxies-absent "$PRODUCTION_APP" '/FINAPP_EDGE_PROXIES/d'
    edge_plant_on edge-proxies-wide "$PRODUCTION_APP" 's#FINAPP_EDGE_PROXIES=.*#FINAPP_EDGE_PROXIES=0.0.0.0/0#'

    edge_plant edge-local-ca
    cat > "$PLANTS/edge-local-ca/deploy/edge/overlays/production/ca.yaml" <<'YAML'
apiVersion: cert-manager.io/v1
kind: Issuer
metadata:
  name: planted
spec:
  selfSigned: {}
YAML
    edge_mutate edge-local-ca "$PRODUCTION_EDGE" 's|^  - \.\./\.\./base$|  - ../../base\n  - ca.yaml|'
}

in_container() {
    case $1 in
        render)
            mkdir -p /scratch/rendered
            for overlay in $OVERLAYS; do
                render_both /repo/deploy "$overlay" "/scratch/rendered/$overlay.yaml"
                printf 'overlay: %s\n' "$overlay" > "/scratch/rendered/$overlay.data.yaml"
            done
            ;;
        plants)
            plant_all
            for directory in "$PLANTS"/*; do
                render_both "$directory/deploy" "$(cat "$directory/overlay")" "$directory/rendered.yaml"
            done
            ;;
        *)
            echo "unknown step: $1" >&2
            exit 2
            ;;
    esac
}

if [ "${1:-}" = "--in-container" ]; then
    in_container "$2"
    exit 0
fi

# ---------------------------------------------------------------------------------------------------
# On the host (a laptop or CI's runner).
# ---------------------------------------------------------------------------------------------------
# shellcheck disable=SC1091
. "$DIR/scanner-pins.sh"

KUSTOMIZE_IMAGE="${KUSTOMIZE_IMAGE:-${KUSTOMIZE_REPOSITORY}@${KUSTOMIZE_DIGEST}}"
KUBECONFORM_IMAGE="${KUBECONFORM_IMAGE:-${KUBECONFORM_REPOSITORY}@${KUBECONFORM_DIGEST}}"
CONFTEST_IMAGE="${CONFTEST_IMAGE:-${CONFTEST_REPOSITORY}@${CONFTEST_DIGEST}}"
TRAEFIK_IMAGE="${TRAEFIK_REPOSITORY}@${TRAEFIK_DIGEST}"
WHOAMI_IMAGE="${WHOAMI_REPOSITORY}@${WHOAMI_DIGEST}"
CURL_IMAGE="${CURL_REPOSITORY}@${CURL_DIGEST}"

SCHEMAS="https://raw.githubusercontent.com/yannh/kubernetes-json-schema/${KUBERNETES_SCHEMAS_COMMIT}/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json"
CRD_SCHEMAS="https://raw.githubusercontent.com/datreeio/CRDs-catalog/${CRD_SCHEMAS_COMMIT}/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

docker info >/dev/null 2>&1 || { echo "Docker is not available: every check runs in a pinned image." >&2; exit 2; }
command -v openssl >/dev/null 2>&1 || { echo "openssl is not available: the throwaway certificate cannot be made." >&2; exit 2; }

RUN="finapp-edge-check-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
VOLUME="$RUN"
NETWORK="$RUN"
CERTS=$(mktemp -d)

status=0

cleanup() {
    docker rm -f "$RUN-public" "$RUN-operator" "$RUN-spool" "$RUN-backend" "$RUN-client" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
    docker volume rm -f "$VOLUME" "$VOLUME-public" "$VOLUME-operator" >/dev/null 2>&1 || true
    rm -rf "$CERTS"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

docker volume create "$VOLUME" >/dev/null

check() {
    label=$1
    shift
    echo "==   $label"
    if "$@"; then
        echo "ok   $label"
    else
        echo "!!   $label FAILED"
        status=1
    fi
}

in_kustomize() {
    docker run --rm --entrypoint /bin/sh \
        -v "$ROOT/deploy:/repo/deploy:ro" -v "$DIR:/repo/infra:ro" -v "$VOLUME:/scratch" \
        "$KUSTOMIZE_IMAGE" /repo/infra/scripts/edge-check.sh --in-container "$1"
}

kubeconform_on() {
    docker run --rm -v "$VOLUME:/scratch" "$KUBECONFORM_IMAGE" \
        -strict -summary -cache /scratch \
        -kubernetes-version "$KUBERNETES_VERSION" -schema-location "$SCHEMAS" -schema-location "$CRD_SCHEMAS" "$1"
}

conftest_on() {
    docker run --rm \
        -v "$DIR/scripts/manifest-policy:/policy:ro" \
        -v "$ROOT/app/src/main/resources/application.yaml:/application/application.yaml:ro" \
        -v "$VOLUME:/scratch:ro" \
        "$CONFTEST_IMAGE" test --no-color --combine --all-namespaces=false --namespace edge \
        --policy /policy/finapp.rego --policy /policy/edge.rego \
        --data /application/application.yaml --data /policy/shutdown-budget.yaml --data "$2" "$1"
}

edge_refused() {
    # edge_refused <plant> <rule id>: the plant's rendered stream must fail the policies and name the rule.
    if output=$(conftest_on "/scratch/edge-plants/$1/rendered.yaml" "/scratch/edge-plants/$1/overlay.yaml" 2>&1); then
        printf '%s\n' "$output"
        echo "!!   plant $1 was NOT refused"
        status=1
    elif printf '%s\n' "$output" | grep -qF -- "[$2]"; then
        echo "ok   plant $1 refused: $(printf '%s\n' "$output" | grep -F -m 1 -- "[$2]" | sed 's/^[[:space:]]*//' | cut -c1-150)"
    else
        printf '%s\n' "$output"
        echo "!!   plant $1 was refused, but not for its reason: the output never says [$2]"
        status=1
    fi
}

check "every overlay renders, the application's and the edge's" in_kustomize render
check "the plants are planted and rendered" in_kustomize plants

for overlay in $OVERLAYS; do
    check "kubeconform $overlay" kubeconform_on "/scratch/rendered/$overlay.yaml"
    check "the edge policies on $overlay" conftest_on "/scratch/rendered/$overlay.yaml" "/scratch/rendered/$overlay.data.yaml"
done

edge_refused edge-mounted-account security-token
edge_refused edge-image-tag images-digest
edge_refused edge-root security-non-root
edge_refused edge-writable security-read-only
edge_refused edge-one-replica edge-controllers
edge_refused edge-no-routes edge-controllers
edge_refused edge-no-role-label edge-controllers
edge_refused edge-forwarded-insecure edge-forwarded
edge_refused edge-operator-forwarded edge-forwarded
edge_refused edge-forwarded-trusted edge-forwarded
edge_refused edge-alias-keep edge-forwarded
edge_refused edge-encoded-slash edge-encoded
edge_refused edge-encoded-absent edge-encoded
edge_refused edge-sanitize edge-encoded
edge_refused edge-plain-listener edge-listener
edge_refused edge-tls-dropped edge-listener
edge_refused edge-kubernetes-provider edge-provider
edge_refused edge-public-operator edge-routes-public
edge_refused edge-public-no-limit edge-routes-public
edge_refused edge-public-no-hold edge-hold
edge_refused edge-hold-on-host edge-hold
edge_refused edge-spool-on-disk edge-spool
edge_refused edge-spool-over-limit edge-spool
edge_refused edge-no-backend-timeout edge-timeouts
edge_refused edge-no-write-timeout edge-timeouts
edge_refused edge-upgrade-forwarded edge-upgrade
edge_refused edge-operator-no-allowlist edge-routes-operator
edge_refused edge-sticky edge-no-affinity
edge_refused edge-reuse edge-no-affinity
edge_refused edge-management-port edge-service-port
edge_refused edge-operator-public edge-operator-internal
edge_refused edge-no-source-address edge-source-address
edge_refused edge-no-preserve edge-source-address
edge_refused edge-nlb-instance edge-source-address
edge_refused edge-ingress edge-no-ingress
edge_refused edge-no-default-deny edge-network
edge_refused edge-operator-internet edge-network
edge_refused edge-proxies-absent edge-proxies
edge_refused edge-proxies-wide edge-proxies
edge_refused edge-local-ca edge-local-ca

# ---------------------------------------------------------------------------------------------------
# The routing: the committed files, in the pinned proxy.
# ---------------------------------------------------------------------------------------------------
# certificate <name> <common name> <dns name>: a throwaway pair in $CERTS.
certificate() {
    (cd "$CERTS" && openssl req -x509 -newkey rsa:2048 -nodes -keyout "$1.key" -out "$1.crt" -days 2 \
        -subj "/CN=$2" -addext "subjectAltName=DNS:$3" >/dev/null 2>&1)
}

# layout <volume> <routes> <edge file> <cert name>: the files as the kubelet lays out a projected volume (..data, a timestamped directory).
layout() {
    tar -C "$CERTS" -cf - . | docker run --rm -i --entrypoint sh -v "$1:/v" -v "$ROOT/deploy/edge/base:/edge:ro" "$TRAEFIK_IMAGE" -c "
        set -e
        mkdir -p /v/..first
        tar -xf - -C /v/..first
        cp /edge/$2 /v/..first/routes.yaml
        cp /edge/$3 /v/..first/edge.yaml
        cp /v/..first/$4.crt /v/..first/tls.crt
        cp /v/..first/$4.key /v/..first/tls.key
        ln -s ..first /v/..data
        for file in routes.yaml edge.yaml tls.crt tls.key; do ln -s ..data/\$file /v/\$file; done
        chmod -R a+rX /v
    "
}

# swap_volume <volume> <directory to create> <shell snippet run in it>: replaces the projected volume the way the kubelet does.
swap_volume() {
    docker run --rm --entrypoint sh -v "$1:/v" "$TRAEFIK_IMAGE" -c "
        set -e
        cd /v
        old=\$(readlink ..data)
        mkdir $2
        cp \$old/routes.yaml \$old/edge.yaml \$old/tls.crt \$old/tls.key $2/
        $3
        chmod -R a+rX $2
        ln -s $2 ..data_tmp
        mv -T ..data_tmp ..data
        rm -rf \$old
    "
}

start_controller() {
    # start_controller <surface> <static file>. The pod's own posture: its numeric user, a read-only root, every capability dropped, and
    # /tmp the memory-backed volume its manifest states.
    docker run -d --name "$RUN-$1" --network "$NETWORK" --network-alias "$1.finapp.test" \
        --user 65532:65532 --read-only --tmpfs /tmp:rw,size=256m,mode=1777 --cap-drop ALL --security-opt no-new-privileges \
        -v "$ROOT/deploy/edge/base/$2:/etc/traefik/traefik.yaml:ro" \
        -v "$VOLUME-$1:/etc/finapp-edge/dynamic:ro" "$TRAEFIK_IMAGE" >/dev/null
}

# client: every request below comes from this one container, so its address is the one the backend must report.
client() {
    docker exec "$RUN-client" "$@"
}

code() {
    client curl -sk --path-as-is --max-time 15 -o /dev/null -w '%{http_code}' "$@" || true
}

expect_code() {
    # expect_code <label> <wanted> <curl args...>
    label=$1 wanted=$2
    shift 2
    got=$(code "$@")
    if [ "$got" = "$wanted" ]; then
        echo "ok   $label: $got"
    else
        echo "!!   $label: wanted $wanted, got $got"
        status=1
    fi
}

# edge_answers_with_headers <label> <wanted status> <curl args...>: a response the edge itself produces still carries HSTS and its siblings.
# Tried a few times: a status that depends on a bucket being empty can miss once.
edge_answers_with_headers() {
    label=$1 wanted=$2
    shift 2
    i=0
    while [ "$i" -lt 25 ]; do
        answer=$(client curl -sk --path-as-is --max-time 15 -D - -o /dev/null "$@" | tr -d '\r')
        if printf '%s\n' "$answer" | head -1 | grep -q " $wanted"; then
            if printf '%s\n' "$answer" | grep -qi '^strict-transport-security: max-age=31536000' \
                && printf '%s\n' "$answer" | grep -qi '^x-content-type-options: nosniff'; then
                echo "ok   $label: $wanted, with the headers"
            else
                printf '%s\n' "$answer" | head -12
                echo "!!   $label: $wanted without HSTS or nosniff"
                status=1
            fi
            return 0
        fi
        i=$((i + 1))
    done
    printf '%s\n' "$answer" | head -12
    echo "!!   $label: never answered $wanted"
    status=1
}

docker network create "$NETWORK" >/dev/null
docker volume create "$VOLUME-public" >/dev/null
docker volume create "$VOLUME-operator" >/dev/null
certificate public public.finapp.test public.finapp.test
certificate operator operator.finapp.test operator.finapp.test
certificate renewed renewed.finapp.test public.finapp.test
layout "$VOLUME-public" public-routes.yaml public-edge.yaml public
layout "$VOLUME-operator" operator-routes.yaml operator-edge.yaml operator

docker run -d --name "$RUN-backend" --network "$NETWORK" --network-alias finapp-app.finapp.svc.cluster.local \
    "$WHOAMI_IMAGE" --port 8080 --verbose >/dev/null
start_controller public traefik.yaml
start_controller operator traefik-operator.yaml
docker run -d --name "$RUN-client" --network "$NETWORK" --entrypoint sleep "$CURL_IMAGE" 600 >/dev/null

PUBLIC=https://public.finapp.test:8443
OPERATOR=https://operator.finapp.test:8443

i=0
while [ "$(code "$PUBLIC/v1/me")" != 200 ] || [ "$(code "$OPERATOR/v1/authentications" -X POST)" != 200 ]; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then
        docker logs "$RUN-public" 2>&1 | tail -20
        echo "!!   the controllers never served their routes"
        exit 1
    fi
    sleep 1
done

echo "==   the public host: an operator route in every spelling that has got past an allow-list reaches nothing"
SAMPLE=abc-123_X
for path in \
    "/v1/operator/merchants/$SAMPLE" \
    "//v1/operator/merchants/$SAMPLE" \
    "/v1//operator/merchants/$SAMPLE" \
    "/v1/./operator/merchants/$SAMPLE" \
    "/v1/me/../operator/merchants/$SAMPLE" \
    "/v1/operator%2Fmerchants/$SAMPLE" \
    "/v1%2Foperator/merchants/$SAMPLE" \
    "/v1/%6Fperator/merchants/$SAMPLE" \
    "/v1/operator%252Fmerchants/$SAMPLE" \
    "/v1/operator/merchants/$SAMPLE/" \
    "/V1/OPERATOR/MERCHANTS/$SAMPLE" \
    "/v1/operator;x=1/merchants/$SAMPLE" \
    "/v1/me/..;/operator/merchants/$SAMPLE" \
    "/v1/operator%00/merchants/$SAMPLE" \
    "/v1/operator%5Cmerchants/$SAMPLE" \
    "/v1/payments/%2e%2e" \
    "/v1/payments/..;" \
    "/v1/payments/..%3B" \
    "/v1/payments/x%252Fy"
do
    got=$(code "$PUBLIC$path")
    case $got in
        400|404) echo "ok   not reached: $got $path" ;;
        *) echo "!!   an operator path answered $got on the public host: $path"; status=1 ;;
    esac
done
for method in GET POST PUT PATCH DELETE; do
    expect_code "$method on an operator path, public host" 404 -X "$method" "$PUBLIC/v1/operator/merchants/$SAMPLE"
done

if docker logs "$RUN-backend" 2>&1 | grep -qi 'operator'; then
    docker logs "$RUN-backend" 2>&1 | grep -i 'operator' | head
    echo "!!   the backend received an operator path from the public host"
    status=1
else
    echo "ok   the backend received no operator path from the public host"
fi

echo "==   the public host: what is listed is served, by method, over TLS, with the headers"
expect_code "a customer route" 200 "$PUBLIC/v1/me"
expect_code "a template, one segment" 200 "$PUBLIC/v1/payments/$SAMPLE"
expect_code "the wrong method" 404 -X POST "$PUBLIC/v1/me"
expect_code "an unlisted method" 404 -X TRACE "$PUBLIC/v1/me"
expect_code "an extra segment" 404 "$PUBLIC/v1/payments/$SAMPLE/nope"
headers=$(client curl -sk -D - -o /dev/null --max-time 15 "$PUBLIC/v1/me" | tr -d '\r')
for header in 'strict-transport-security: max-age=31536000' 'x-content-type-options: nosniff' 'x-frame-options: DENY' 'referrer-policy: no-referrer' "content-security-policy: default-src 'none'"; do
    if printf '%s\n' "$headers" | grep -qi -- "^$header"; then
        echo "ok   $header"
    else
        printf '%s\n' "$headers"
        echo "!!   the response lacks: $header"
        status=1
    fi
done
for line in 'strict-transport-security' 'x-frame-options'; do
    if [ "$(printf '%s\n' "$headers" | grep -ci -- "^$line:")" -ne 1 ]; then
        echo "!!   $line is answered other than once"
        status=1
    fi
done

echo "==   an encoded spelling of a public path is routed as that path, and the backend receives what was sent"
for spelling in '/v1/p%61yments/abc-123' '/v1/payments/a%2eb'; do
    answer=$(client curl -sk --path-as-is --max-time 15 "$PUBLIC$spelling" | tr -d '\r')
    if printf '%s\n' "$answer" | grep -qxF "GET $spelling HTTP/1.1"; then
        echo "ok   served, and received as sent: $spelling"
    else
        printf '%s\n' "$answer" | head -12
        echo "!!   $spelling was not received as sent"
        status=1
    fi
done

echo "==   an h2c upgrade stops here"
answer=$(client curl --http1.1 -sk --max-time 15 -H 'Connection: Upgrade, HTTP2-Settings' -H 'Upgrade: h2c' \
    -H 'HTTP2-Settings: AAMAAABkAAQAoAAAAAIAAAAA' "$PUBLIC/v1/me" | tr -d '\r')
if printf '%s\n' "$answer" | grep -qx 'GET /v1/me HTTP/1.1' && ! printf '%s\n' "$answer" | grep -qi '^Upgrade:\|^Http2-Settings:'; then
    echo "ok   the backend received no Upgrade header"
else
    printf '%s\n' "$answer" | head -14
    echo "!!   an h2c upgrade reached the backend"
    status=1
fi

echo "==   TLS is the only way in"
if client curl -sk --max-time 5 -o /dev/null --tlsv1.1 --tls-max 1.1 "$PUBLIC/v1/me" >/dev/null 2>&1; then
    echo "!!   TLS 1.1 was accepted"
    status=1
else
    echo "ok   TLS 1.1 is refused"
fi
if client curl -s --max-time 5 -o /dev/null http://public.finapp.test:80/ >/dev/null 2>&1; then
    echo "!!   something answers on port 80"
    status=1
else
    echo "ok   nothing listens on port 80"
fi
if client curl -sk --max-time 5 -o /dev/null --resolve public.finapp.test.invalid:8443:"$(client getent hosts public.finapp.test | cut -d' ' -f1)" https://public.finapp.test.invalid:8443/v1/me >/dev/null 2>&1; then
    echo "!!   a handshake naming nothing this edge holds was answered"
    status=1
else
    echo "ok   a handshake naming nothing this edge holds is refused"
fi

echo "==   the client's address is the connection's"
CLIENT_ADDRESS=$(client getent hosts "$(client hostname)" | cut -d' ' -f1)
answer=$(client curl -sk --max-time 15 -H 'X-Forwarded-For: 203.0.113.9' -H 'X-Real-Ip: 203.0.113.10' -H 'Forwarded: for=203.0.113.11' \
    -H 'X-Forwarded-Prefix: /elsewhere' -H 'X_Forwarded_For: 203.0.113.12' -H 'X-Forwarded-Host: elsewhere' "$PUBLIC/v1/me" | tr -d '\r')
if printf '%s\n' "$answer" | grep -qx "X-Forwarded-For: $CLIENT_ADDRESS"; then
    echo "ok   the backend sees X-Forwarded-For: the connection's address alone"
else
    printf '%s\n' "$answer"
    echo "!!   the backend's X-Forwarded-For is not the connection's address, $CLIENT_ADDRESS"
    status=1
fi
for smuggled in '203.0.113' 'X-Forwarded-Prefix' 'X_Forwarded_For' 'Forwarded: for' 'elsewhere'; do
    if printf '%s\n' "$answer" | grep -qi -- "$smuggled"; then
        printf '%s\n' "$answer"
        echo "!!   a client-supplied value reached the backend: $smuggled"
        status=1
    else
        echo "ok   did not reach the backend: $smuggled"
    fi
done

client sh -c 'head -c 1048576 /dev/zero > /tmp/one-mib; head -c 3145728 /dev/zero > /tmp/three-mib; head -c 1900000 /dev/zero > /tmp/almost-two'

echo "==   what one source can hold: five in flight on the door tier, whatever Host it names"
i=0
while [ "$i" -lt 5 ]; do
    docker exec -d "$RUN-client" curl -sk --max-time 10 --limit-rate 30k -X POST --data-binary @/tmp/one-mib -H "Host: holder-$i.invalid" -o /dev/null "$PUBLIC/v1/authentications"
    i=$((i + 1))
done
sleep 2
expect_code "a sixth from the same source, naming another Host" 429 -X POST --data-binary @/tmp/one-mib -H 'Host: another.invalid' "$PUBLIC/v1/authentications"
sleep 9

echo "==   a body held open past the read timeout is cut"
held=$(client curl -sk --max-time 40 --limit-rate 10k -X POST --data-binary @/tmp/one-mib -H 'Content-Type: application/json' -o /dev/null -w '%{http_code} %{time_total}' "$PUBLIC/v1/providers/payments/webhooks" || true)
held_code=${held%% *}
held_seconds=${held##* }
if [ "$held_code" != 200 ] && [ "${held_seconds%%.*}" -lt 30 ]; then
    echo "ok   cut after ${held_seconds}s, $held_code"
else
    echo "!!   a held body answered $held"
    status=1
fi

echo "==   the size limits and the per-source limit"
got=$(client curl -sk --max-time 30 -X POST --data-binary @/tmp/one-mib -H 'Content-Type: application/json' "$PUBLIC/v1/providers/payments/webhooks" -w '\n%{http_code}' | tr -d '\r')
if printf '%s\n' "$got" | tail -1 | grep -qx 200 && printf '%s\n' "$got" | grep -qx 'Content-Length: 1048576'; then
    echo "ok   a provider callback at the application's limit arrives whole"
else
    printf '%s\n' "$got" | tail -12
    echo "!!   a provider callback at the application's limit did not arrive whole"
    status=1
fi
expect_code "a body over the cap, declared" 413 -X POST --data-binary @/tmp/three-mib -H 'Content-Type: application/json' "$PUBLIC/v1/providers/payments/webhooks"
expect_code "a body over the cap, chunked" 413 -X POST --data-binary @/tmp/three-mib -H 'Content-Type: application/json' -H 'Transfer-Encoding: chunked' "$PUBLIC/v1/providers/payments/webhooks"
expect_code "the settlement upload's larger cap, on its own route" 200 -X POST --data-binary @/tmp/three-mib -H 'Content-Type: application/json' "$OPERATOR/v1/operator/settlement/files"
expect_code "the operator surface's default cap" 413 -X POST --data-binary @/tmp/three-mib -H 'Content-Type: application/json' "$OPERATOR/v1/operator/merchants"

# Each request names a different forged X-Forwarded-For. A limit keyed on that header would count sixty sources and limit none.
burst=""
i=0
while [ "$i" -lt 60 ]; do
    [ "$i" -eq 0 ] || burst="$burst --next"
    burst="$burst -sk --max-time 30 -X POST -H X-Forwarded-For:198.51.100.$i -o /dev/null -w %{http_code}\\n $PUBLIC/v1/authentications"
    i=$((i + 1))
done
# shellcheck disable=SC2086
codes=$(client curl -Z --parallel-max 50 $burst)
served=$(printf '%s\n' "$codes" | grep -c '^200$' || true)
limited=$(printf '%s\n' "$codes" | grep -c '^429$' || true)
if [ "$served" -gt 0 ] && [ "$limited" -gt 0 ]; then
    echo "ok   a burst from one source naming sixty forged addresses: $served served, $limited limited, so the key is the connection"
else
    printf '%s\n' "$codes" | sort | uniq -c
    echo "!!   a burst from one source was not limited: $served served, $limited limited"
    status=1
fi

edge_answers_with_headers "the per-source limit's own answer" 429 -X POST "$PUBLIC/v1/authentications"
edge_answers_with_headers "the body cap's own answer" 413 -X POST --data-binary @/tmp/three-mib -H 'Content-Type: application/json' "$PUBLIC/v1/providers/payments/webhooks"

echo "==   a tier's limit is one bucket across all its routes"
sleep 3
# door_burst <url>: thirty POSTs at once, half again the tier's burst of twenty; prints how many were served.
door_burst() {
    burst=""
    i=0
    while [ "$i" -lt 30 ]; do
        [ "$i" -eq 0 ] || burst="$burst --next"
        burst="$burst -sk --max-time 30 -X POST -o /dev/null -w %{http_code}\\n $1"
        i=$((i + 1))
    done
    # shellcheck disable=SC2086
    client curl -Z --parallel-max 30 $burst | grep -c '^200$' || true
}
first=$(door_burst "$PUBLIC/v1/authentications")
second=$(door_burst "$PUBLIC/v1/registrations")
# Two buckets would have served at least the second route's own twenty.
if [ "$first" -gt 0 ] && [ "$second" -lt 20 ]; then
    echo "ok   one bucket across two routes: the first served $first of 30, the second $second of 30 straight after"
else
    echo "!!   the door tier is not one bucket: the first route served $first of 30, the second $second of 30"
    status=1
fi

echo "==   the operator host"
expect_code "an operator route" 200 "$OPERATOR/v1/operator/merchants/$SAMPLE"
expect_code "an operator route outside the prefix" 200 -X POST "$OPERATOR/v1/ledger/adjustments"
expect_code "a session door" 200 -X POST "$OPERATOR/v1/authentications"
expect_code "a customer route is not here" 404 "$OPERATOR/v1/me/accounts"
expect_code "a provider callback is not here" 404 -X POST "$OPERATOR/v1/providers/payments/webhooks"

echo "==   a renewed certificate, on a swapped volume, with no restart"
subject() {
    client curl -skv --max-time 5 -o /dev/null "$PUBLIC/v1/me" 2>&1 | grep -i 'subject:' | head -1
}
if ! subject | grep -q 'public.finapp.test'; then
    subject
    echo "!!   the public edge did not start on its own certificate"
    status=1
fi
swap_volume "$VOLUME-public" ..second 'cp ..first/renewed.crt ..second/tls.crt; cp ..first/renewed.key ..second/tls.key'
i=0
until subject | grep -q 'renewed.finapp.test'; do
    i=$((i + 1))
    if [ "$i" -gt 30 ]; then
        subject
        echo "!!   the renewed certificate was never served"
        status=1
        break
    fi
    sleep 1
done
[ "$i" -gt 30 ] || echo "ok   the renewed certificate is served without a restart, after ${i}s"

echo "==   the operator host's address allow-list refuses a source outside it"
swap_volume "$VOLUME-operator" ..second "sed -i -e 's#10.0.0.0/8#192.0.2.0/24#' -e 's#172.16.0.0/12#198.51.100.0/24#' -e 's#192.168.0.0/16#203.0.113.0/24#' ..second/edge.yaml"
i=0
until [ "$(code "$OPERATOR/v1/operator/merchants/$SAMPLE")" = 403 ]; do
    i=$((i + 1))
    if [ "$i" -gt 30 ]; then
        echo "!!   a source outside the internal ranges was not refused"
        status=1
        break
    fi
    sleep 1
done
[ "$i" -gt 30 ] || echo "ok   a source outside the internal ranges is refused: 403"
edge_answers_with_headers "the address allow-list's own answer" 403 "$OPERATOR/v1/operator/merchants/$SAMPLE"

echo "==   a full spool refuses the large body and still serves the small ones"
docker run -d --name "$RUN-spool" --network "$NETWORK" \
    --user 65532:65532 --read-only --tmpfs /tmp:rw,size=4m,mode=1777 --cap-drop ALL --security-opt no-new-privileges \
    -v "$ROOT/deploy/edge/base/traefik.yaml:/etc/traefik/traefik.yaml:ro" \
    -v "$VOLUME-public:/etc/finapp-edge/dynamic:ro" "$TRAEFIK_IMAGE" >/dev/null
SPOOL_ADDRESS=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$RUN-spool")
RESOLVE="public.finapp.test:8443:$SPOOL_ADDRESS"
i=0
until [ "$(code --resolve "$RESOLVE" "$PUBLIC/v1/me")" = 200 ]; do
    i=$((i + 1))
    if [ "$i" -gt 30 ]; then
        echo "!!   the small-spool controller never served"
        status=1
        break
    fi
    sleep 1
done
# Three uploads abandoned part way: each leaves its spool behind until the proxy's next garbage collection.
for abandoned in 1 2 3; do
    client curl -sk --max-time 2 --limit-rate 800k --resolve "$RESOLVE" -X POST --data-binary @/tmp/almost-two -H 'Content-Type: application/json' -o /dev/null "$PUBLIC/v1/providers/payments/webhooks" || true
done
expect_code "a large body on a full spool" 500 --resolve "$RESOLVE" -X POST --data-binary @/tmp/almost-two -H 'Content-Type: application/json' "$PUBLIC/v1/providers/payments/webhooks"
expect_code "a small request beside a full spool" 200 --resolve "$RESOLVE" "$PUBLIC/v1/me"
if [ "$(docker inspect -f '{{.State.Running}} {{.RestartCount}}' "$RUN-spool")" = "true 0" ]; then
    echo "ok   the controller is still running, never restarted"
else
    echo "!!   the controller did not survive a full spool"
    status=1
fi

if [ "$status" -eq 0 ]; then
    echo "ok   every check green, every plant refused for its reason, the proxy holds on every spelling"
fi
exit $status
