#!/bin/bash
# Isolation spike — DETAIL app (App-2). Consumes the master's ICatalogService via
# the static EDEF and displays the current selection. Start run-spike-master.sh
# FIRST, then this in another terminal.
#
# Multi-frame grid: the detail reads the frame count from the master's published
# layout, so it tiles N windows automatically. Set SPIKE_FRAMES on the MASTER only
# — the master is the single source of truth for N.

PRODUCT_UID="com.kk.pde.ds.spike.detail.product"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -d "$SCRIPT_DIR/plugins" ]; then
    PRODUCT_DIR="$SCRIPT_DIR"
else
    BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
    case "$(uname -s)" in
        Darwin)
            PRODUCT_DIR="$BASE_DIR/target/products/$PRODUCT_UID/macosx/cocoa/x86_64/Eclipse.app/Contents/Eclipse"
            ;;
        Linux)
            PRODUCT_DIR="$BASE_DIR/target/products/$PRODUCT_UID/linux/gtk/x86_64"
            ;;
        *)
            echo "ERROR: Unsupported OS. On Windows, use run-spike-detail.bat instead."
            exit 1
            ;;
    esac
fi

OSGI_JAR=$(ls "$PRODUCT_DIR/plugins/org.eclipse.osgi_"*.jar 2>/dev/null | head -1)

if [ -z "$OSGI_JAR" ]; then
    echo "ERROR: Could not find org.eclipse.osgi jar in: $PRODUCT_DIR/plugins/"
    echo "Hint: run 'mvn clean verify' from the project root first."
    exit 1
fi

echo "Starting spike DETAIL (App-2): $OSGI_JAR"
cd "$PRODUCT_DIR"
java "$@" -jar "$OSGI_JAR" -configuration configuration -console -consoleLog
