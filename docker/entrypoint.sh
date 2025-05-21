#!/bin/bash

fix_permissions() {
    chmod 777 /opt/teamcity/logs/* /data/teamcity_server/datadir/* -R
}

trap fix_permissions SIGTERM SIGINT
/opt/teamcity/bin/teamcity-server.sh run &
wait $!