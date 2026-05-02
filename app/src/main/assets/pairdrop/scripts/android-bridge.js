(function () {
    if (!window.PairDropAndroid || !window.Events) return;

    const Native = window.PairDropAndroid;

    async function consumePendingShares() {
        if (!window.Events) {
            setTimeout(consumePendingShares, 250);
            return;
        }

        let payload;
        try {
            payload = JSON.parse(Native.consumePendingShares() || "{}");
        } catch (error) {
            Native.log("Could not parse pending shares: " + error);
            return;
        }

        const sharedFiles = payload.files || [];
        const sharedText = payload.text || "";
        if (!sharedFiles.length && !sharedText) return;

        try {
            const files = [];
            for (const item of sharedFiles) {
                const response = await fetch(item.url);
                if (!response.ok) throw new Error("Could not load shared file " + item.name);
                const blob = await response.blob();
                files.push(new File([blob], item.name, { type: item.mime || blob.type || "" }));
            }

            Events.fire("activate-share-mode", {
                files: files,
                text: sharedText
            });
            Native.log("Android share payload activated: " + files.length + " file(s), text=" + !!sharedText);
            Native.keepAlive();
        } catch (error) {
            Native.log("Could not import Android share payload: " + error);
        }
    }

    async function ensurePendingSharesActivated() {
        if (window.pairDrop && window.pairDrop.peersUI && window.pairDrop.peersUI.shareMode.active) {
            return true;
        }
        await consumePendingShares();
        return !!(window.pairDrop && window.pairDrop.peersUI && window.pairDrop.peersUI.shareMode.active);
    }

    async function saveReceivedFiles(detail) {
        if (!detail || !detail.files || !detail.files.length) return;

        try {
            Native.keepAlive();
            for (const file of detail.files) {
                const params = new URLSearchParams({
                    name: file.name || "PairDrop file",
                    mime: file.type || "application/octet-stream"
                });
                const response = await fetch("/native/received-file?" + params.toString(), {
                    method: "POST",
                    body: file
                });
                if (!response.ok) throw new Error("Native save failed for " + file.name);
            }

            if (window.Localization) {
                Events.fire("notify-user", Localization.getTranslation("notifications.download-successful", null, {
                    descriptor: detail.files.length === 1 ? detail.files[0].name : "files"
                }));
            }
            Native.onTransferProgress(detail.peerId || "", 1, "process");
        } catch (error) {
            Native.log("Could not save received files: " + error);
            Events.fire("notify-user", "Could not save received files");
        }
    }

    window.PairDropNative = {
        consumePendingShares: consumePendingShares,
        ensurePendingSharesActivated: ensurePendingSharesActivated,
        ready: true,
        handlesDownloads: function () {
            try {
                return Native.handlesDownloads();
            } catch (_) {
                return false;
            }
        }
    };

    Events.on("ws-connected", function () {
        setTimeout(consumePendingShares, 250);
    });

    Events.on("set-progress", function (event) {
        const detail = event.detail || {};
        Native.onTransferProgress(detail.peerId || "", Number(detail.progress || 0), detail.status || "");
    });

    Events.on("files-received", function (event) {
        saveReceivedFiles(event.detail || {});
    });

    Events.on("files-transfer-request", function (event) {
        const detail = event.detail || {};
        let nativeWillRespond = false;
        try {
            nativeWillRespond = Native.onIncomingTransferRequest(
                detail.peerId || "",
                JSON.stringify(detail.request || {})
            );
        } catch (error) {
            Native.log("Could not hand transfer request to Android: " + error);
        }

        if (nativeWillRespond) return;
        if (!Native.autoAcceptIncoming()) return;

        Events.fire("respond-to-files-transfer-request", {
            to: detail.peerId,
            accepted: true
        });
    });
})();
