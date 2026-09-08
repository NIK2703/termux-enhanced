#!/data/data/com.termux/files/usr/bin/sh
# Background storm-test driver for the termux-enhanced debug build.
OUT=/data/data/com.termux/files/home/projects/termux-enhanced/storm_results.txt
: > "$OUT"
echo "[$(date +%H:%M:%S)] driver started" >> "$OUT"

# 1. restart the debug app
su -c "am force-stop com.termux.debug; logcat -c; am start -n com.termux.debug/com.termux.app.TermuxActivity" >/dev/null 2>&1
echo "[$(date +%H:%M:%S)] app started" >> "$OUT"

# 2. wait for the service to bind (session count != -1)
i=0
svc=-1
while [ $i -lt 40 ]; do
    sleep 2
    i=$((i+1))
    svc=$(su -c "logcat -d" 2>/dev/null | grep "session count:" | tail -1 | grep -oE "\-?[0-9]+$")
    if [ -n "$svc" ] && [ "$svc" != "-1" ]; then
        break
    fi
    su -c "am start -n com.termux.debug/com.termux.app.TermuxDebugActivity --es cmd 'session count'" >/dev/null 2>&1
done
echo "[$(date +%H:%M:%S)] service ready: sessions=$svc (tries=$i)" >> "$OUT"
if [ "$svc" = "-1" ] || [ -z "$svc" ]; then
    echo "STORM DRIVER FAIL: service never bound" >> "$OUT"
    exit 1
fi

# 3. run the storm (3 rounds), poll for the result
su -c "logcat -c" >/dev/null 2>&1
su -c "am start -n com.termux.debug/com.termux.app.TermuxDebugActivity --es cmd 'storm' --es arg 10" >/dev/null 2>&1
echo "[$(date +%H:%M:%S)] storm triggered (3 rounds)" >> "$OUT"

i=0
result=""
while [ $i -lt 120 ]; do
    sleep 2
    i=$((i+1))
    result=$(su -c "logcat -d" 2>/dev/null | grep -E "STORM (PASS|FAIL total)" | tail -1)
    if [ -n "$result" ]; then break; fi
done

if [ -z "$result" ]; then
    echo "STORM DRIVER FAIL: no result after $((i*2))s" >> "$OUT"
fi

# 4. dump everything
echo "[$(date +%H:%M:%S)] result line:" >> "$OUT"
echo "$result" >> "$OUT"
echo "[$(date +%H:%M:%S)] full STORM log:" >> "$OUT"
su -c "logcat -d" 2>/dev/null | grep -E "STORM|KBTrace" >> "$OUT"
echo "[$(date +%H:%M:%S)] driver finished" >> "$OUT"
