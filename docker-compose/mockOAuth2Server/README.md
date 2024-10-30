# TLS Keystore For MockOAuth2Server

Run your [own CA](https://pki-tutorial.readthedocs.io/en/latest/simple/) and issue yourself a
[key pair](./mockoauth2server.p12).<br>
If you want/need to repeat the process:

1. check out the 2022 branch (or whatever branch appears to be the most up-to-date one) of this
   [git repo](https://bitbucket.org/stefanholek/pki-example-1) and
2. change the policy "match_pol" of the signing ca config file to mark all fields as "supplied" so you get to set 
   the `DC`, `OU`, and `O` parts of the X509 certificate yourself:
    ```shell
   diff work/git/3rd_party/pki-example-1/etc/signing-ca.conf work/software/ca/etc/signing-ca.conf
    68,70c68,70
    < domainComponent         = match                 # Must match 'simple.org'
    < organizationName        = match                 # Must match 'Simple Inc'
    < organizationalUnitName  = optional              # Included if present
    ---
    > domainComponent         = supplied                 # Must match 'simple.org'
    > organizationName        = supplied                 # Must match 'Simple Inc'
    > organizationalUnitName  = supplied              # Included if present
    ```
3. follow the instructions to initialize the root and signing CAs
4. create a sub-folder `certs` 
5. then generated a new key pair for the Oauth2 Mock Server like so (note that the `SAN` must match the service name in
   the [docker-compose.yml](../docker-compose.yaml)):
    ```shell
    SAN=DNS:mock-oauth2-server,DNS:localhost,DNS:host.docker.internal \
    openssl req -new \
        -config etc/server.conf \
        -out certs/mockoauth2server.csr \
        -keyout certs/mockoauth2server.key
        
    openssl ca \
        -config etc/signing-ca.conf \
        -in certs/mockoauth2server.csr \
        -out certs/mockoauth2server.crt \
        -extensions server_ext
        
    openssl pkcs12 -export \
        -name "OAuth2 Local Development" \
        -inkey certs/mockoauth2server.key \
        -in certs/mockoauth2server.crt \
        -out certs/mockoauth2server.p12
   ```
