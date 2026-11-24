#!/bin/sh
# Certbot deploy hook: install only a verified certificate, then reload in place.
set -eu
domain=${JIBRO_CERT_HOST:-jibro.live}
case " ${RENEWED_DOMAINS:-} " in
    *" $domain "*) ;;
    *) exit 0 ;;
esac
lineage=${RENEWED_LINEAGE:?Certbot must provide the renewed lineage}
tls=${JIBRO_TLS_DIRECTORY:-/opt/jibro/tls}
container=${JIBRO_WEB_CONTAINER:-jibro-production-web-1}
openssl x509 -in "$lineage/fullchain.pem" -noout -checkhost "$domain" >/dev/null
openssl x509 -in "$lineage/fullchain.pem" -noout -checkend 86400 >/dev/null
cert_key=$(openssl x509 -in "$lineage/fullchain.pem" -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum)
private_key=$(openssl pkey -in "$lineage/privkey.pem" -pubout -outform DER | sha256sum)
[ "$cert_key" = "$private_key" ] || exit 1
backup=$(mktemp -d "$tls/.renewal-backup.XXXXXX")
changed=0
cleanup() {
    if [ "$changed" -eq 1 ]; then
        cp -p "$backup/fullchain.pem" "$tls/fullchain.pem"
        cp -p "$backup/privkey.pem" "$tls/privkey.pem"
        docker exec "$container" nginx -s reload || true
    fi
    rm -f "$backup/fullchain.pem" "$backup/privkey.pem" "$tls/fullchain.pem.new" "$tls/privkey.pem.new"
    rmdir "$backup"
}
trap cleanup 0
cp -p "$tls/fullchain.pem" "$backup/fullchain.pem"
cp -p "$tls/privkey.pem" "$backup/privkey.pem"
install -m 644 "$lineage/fullchain.pem" "$tls/fullchain.pem.new"
install -m 600 "$lineage/privkey.pem" "$tls/privkey.pem.new"
changed=1
mv "$tls/fullchain.pem.new" "$tls/fullchain.pem"
mv "$tls/privkey.pem.new" "$tls/privkey.pem"
if docker exec "$container" nginx -t && docker exec "$container" nginx -s reload; then
    changed=0
    exit 0
fi
exit 1
