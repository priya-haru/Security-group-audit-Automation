/root/bin/fetch_apache_status.sh
[root@de0-vsiaas-1474 sh-2b06-2d37-sys]# cat /root/bin/fetch_apache_status.sh
#!/bin/bash

### This script is executed via crontab every 5 mins ####
###                written by ahamt4tx               ####
###             Please Refer to S-1285460            ####

OUTPUT_FILE="/var/lib/node_exporter/apache_status.prom"

servers=(
"https://de0-2d37-3d-p01.eu.airbus.corp/server-status?auto"
"https://de0-2d37-3d-p02.eu.airbus.corp/server-status?auto"
"https://de0-viaas-1385.eu.airbus.corp/server-status?auto"
"https://de0-viaas-1386.eu.airbus.corp/server-status?auto"
"https://de0-viaas-1387.eu.airbus.corp/server-status?auto"
"https://de0-viaas-1389.eu.airbus.corp/server-status?auto"
"https://de0-viaas-1390.eu.airbus.corp/server-status?auto"
"https://de0-viaas-1391.eu.airbus.corp/server-status?auto"
"https://de0-viaas-1392.eu.airbus.corp/server-status?auto"
"https://de0-viaas-1393.eu.airbus.corp/server-status?auto"
"https://de0-viaas-1394.eu.airbus.corp/server-status?auto"
"https://de0-viaas-1395.eu.airbus.corp/server-status?auto"

)

server_names=(
"de0-2d37-3d-p01"
"de0-2d37-3d-p02"
"de0-viaas-1385"
"de0-viaas-1386"
"de0-viaas-1387"
"de0-viaas-1389"
"de0-viaas-1390"
"de0-viaas-1391"
"de0-viaas-1392"
"de0-viaas-1393"
"de0-viaas-1394"
"de0-viaas-1395"
)

while true; do

# Output Prometheus header
echo "# HELP apache_status Apache HTTPD metrics via server-status" > "$OUTPUT_FI                                                                                                                                                             LE"

# Loop through each server in the list
for i in "${!servers[@]}"; do
    url="${servers[$i]}"
    name="${server_names[$i]}"

    # Fetch the server-status data
    response=$(curl -s --max-time 5 "$url")

    if [[ -z "$response" ]]; then
        echo "# ERROR: Could not fetch from $url"
        continue
    fi

    # Extract the metrics from the response using grep and awk
    total_accesses=$(echo "$response" | grep -oP '^Total Accesses:\s*\K\d+')
    total_kbytes=$(echo "$response" | grep -oP '^Total kBytes:\s*\K\d+(\.\d+)?')
    bytes_per_sec=$(echo "$response" | grep -oP '^BytesPerSec:\s*\K\d+(\.\d+)?')
    bytes_per_req=$(echo "$response" | grep -oP '^BytesPerReq:\s*\K\d+(\.\d+)?')
    busy_workers=$(echo "$response" | grep -oP '^BusyWorkers:\s*\K\d+')
    idle_workers=$(echo "$response" | grep -oP '^IdleWorkers:\s*\K\d+')

#    # Debugging: print out the extracted values
#    echo "Extracted for $name:"
#    echo "Total Accesses: $total_accesses"
#    echo "Total kBytes: $total_kbytes"
#    echo "BytesPerSec: $bytes_per_sec"
#    echo "BytesPerReq: $bytes_per_req"
#    echo "BusyWorkers: $busy_workers"
#    echo "IdleWorkers: $idle_workers"



    # Output the metrics in Prometheus format, using server name as the instance
    echo "apache_total_accesses{instance=\"$name\"} $total_accesses" >> "$OUTPUT                                                                                                                                                             _FILE"
    echo "apache_total_kbytes{instance=\"$name\"} $total_kbytes" >> "$OUTPUT_FIL                                                                                                                                                             E"
    echo "apache_bytes_per_second{instance=\"$name\"} $bytes_per_sec" >> "$OUTPU                                                                                                                                                             T_FILE"

    echo "apache_bytes_per_request{instance=\"$name\"} $bytes_per_req" >> "$OUTP                                                                                                                                                             UT_FILE"
    echo "apache_busy_workers{instance=\"$name\"} $busy_workers" >> "$OUTPUT_FIL                                                                                                                                                             E"
    echo "apache_idle_workers{instance=\"$name\"} $idle_workers" >> "$OUTPUT_FIL                                                                                                                                                             E"

## Output Prometheus header
#echo "# HELP apache_status Apache HTTPD metrics via server-status" > "$OUTPUT_F                                                                                                                                                             ILE"
done

      wait

done
