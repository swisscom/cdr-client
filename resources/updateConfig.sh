#!/bin/bash

echo "############################"
echo "############################"
echo "############################"
echo "############################"
echo
echo
echo

echo "Please input tenant-id:"
read tenantId
sed -i "s/tenant-id: .*/tenant-id: $tenantId/" "$1"
echo "tenant-id updated in $1"

echo "Please input client-id:"
read clientId
sed -i "s/client-id: .*/client-id: $clientId/" "$1"
echo "client-id updated in $1"

echo "Please input client-secret:"
read clientSecret
sed -i "s/client-secret: .*/client-secret: $clientSecret/" "$1"
echo "client-secret updated in $1"

echo "Please manually start the Application again"

echo
echo
echo
echo "############################"
echo "############################"
echo "############################"
echo "############################"
