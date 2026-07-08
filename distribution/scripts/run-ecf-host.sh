#!/bin/bash
# ECF Remote Services demo — HOST process.
# Exports IRemoteGreet via ECF Generic provider on ecftcp://localhost:3288/server.
# Start this FIRST, then run-ecf-consumer.sh in another terminal.

PRODUCT_UID="com.kk.pde.ds.ecf.host.product"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Detect if we're inside a product directory (plugins/ sits next to us)
# or in the source tree (distribution/scripts/)
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
            echo "ERROR: Unsupported OS. On Windows, use run-ecf-host.bat instead."
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

echo "Starting ECF HOST: $OSGI_JAR"
cd "$PRODUCT_DIR"
java "$@" -jar "$OSGI_JAR" -configuration configuration -console -consoleLog
