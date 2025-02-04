# Create Key Pair
```bash
openssl \
  req \
  -newkey rsa:4096 \
  -nodes \
  -keyout key.pem \
  -x509 \
  -sha256 \
  -days 3650 \
  -subj "/C=CH/ST=Zurich/L=Zurich/O=Swisscom (Schweiz) AG/OU=Swisscom Health/CN=Swisscom Health CDR Team" \
  -addext "subjectAltName = DNS:*.microsoftonline.com, DNS:graph.microsoft.com, DNS:aka.ms" \
  -out cert.pem
```
ref: https://docs.joshuatz.com/cheatsheets/security/self-signed-ssl-certs/#openssl---temporary-csr---prompt-based