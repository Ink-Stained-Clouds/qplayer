import QtQuick
import md3.Core
Item {
    id: control
    
    property real value: 0.0
    property bool indeterminate: false
    property bool wavy: false

    // Override to use custom colours (e.g. white on the dark lyric backdrop).
    property color activeColor: _colors.primary
    property color trackColor: _colors.surfaceContainerHighest

    implicitWidth: 200
    implicitHeight: wavy ? 16 : 4

    property var _colors: Theme.color
    
    // Animation control
    property bool _initialized: false
    Component.onCompleted: _initialized = true
    
    property real _visualValue: Math.max(0.0, Math.min(1.0, control.value))
    Behavior on _visualValue {
        enabled: control._initialized
        NumberAnimation { duration: 200; easing.type: Easing.OutQuad }
    }
    
    // Standard Linear Progress
    Rectangle {
        id: track
        anchors.fill: parent
        visible: !control.wavy
        color: control.trackColor
        radius: height / 2
        clip: true
        
        // Determinate Indicator
        Rectangle {
            visible: !control.indeterminate
            height: parent.height
            width: parent.width * control._visualValue
            color: control.activeColor
            radius: height / 2
        }
        
        // Indeterminate Indicator
        Item {
            anchors.fill: parent
            visible: control.indeterminate
            
            // First bar
            Rectangle {
                id: bar1
                height: parent.height
                color: control.activeColor
                radius: height / 2
                
                SequentialAnimation {
                    running: control.indeterminate && control.visible && !control.wavy
                    loops: Animation.Infinite
                    
                    ParallelAnimation {
                        NumberAnimation { target: bar1; property: "x"; from: -parent.width; to: parent.width; duration: 2000; easing.type: Easing.InOutCubic }
                        SequentialAnimation {
                            NumberAnimation { target: bar1; property: "width"; from: 0; to: parent.width * 0.5; duration: 1000; easing.type: Easing.OutCubic }
                            NumberAnimation { target: bar1; property: "width"; from: parent.width * 0.5; to: 0; duration: 1000; easing.type: Easing.InCubic }
                        }
                    }
                }
            }
            
            // Second bar (delayed)
            Rectangle {
                id: bar2
                height: parent.height
                color: control.activeColor
                radius: height / 2
                
                SequentialAnimation {
                    running: control.indeterminate && control.visible && !control.wavy
                    loops: Animation.Infinite
                    
                    PauseAnimation { duration: 1000 }
                    
                    ParallelAnimation {
                        NumberAnimation { target: bar2; property: "x"; from: -parent.width; to: parent.width; duration: 2000; easing.type: Easing.InOutCubic }
                        SequentialAnimation {
                            NumberAnimation { target: bar2; property: "width"; from: 0; to: parent.width * 0.5; duration: 1000; easing.type: Easing.OutCubic }
                            NumberAnimation { target: bar2; property: "width"; from: parent.width * 0.5; to: 0; duration: 1000; easing.type: Easing.InCubic }
                        }
                    }
                }
            }
        }
    }

    // Wavy Linear Progress
    Canvas {
        id: wavyCanvas
        visible: control.wavy
        anchors.fill: parent
        antialiasing: true

        // Trigger repaint when dependencies change
        property color trackColor: control.trackColor
        property color activeColor: control.activeColor
        // Raw value, NOT _visualValue: its Behavior restarts every frame when the
        // caller updates value per-frame (smooth source), which freezes it. Callers
        // that want easing should smooth the value they pass in.
        property real progress: control.value

        onTrackColorChanged: requestPaint()
        onActiveColorChanged: requestPaint()
        onProgressChanged: requestPaint()

        property real phase: 0.0

        // Drive the wave phase with a Timer (qml4j does not support
        // "NumberAnimation on property" attach syntax).
        // interval=16ms ≈ 60fps; increment 0.1 rad/frame ≈ 1Hz full cycle.
        Timer {
            running: control.wavy && control.visible
            interval: 16
            repeat: true
            onTriggered: {
                wavyCanvas.phase = (wavyCanvas.phase + 0.1) % (Math.PI * 2)
                wavyCanvas.requestPaint()
            }
        }

        onVisibleChanged: if (visible) requestPaint()
        Component.onCompleted: requestPaint()

        onPaint: {
            var ctx = getContext("2d");
            if (!ctx) return;
            ctx.reset();

            var w = width;
            var h = height;
            if (w <= 0 || h <= 0) return;

            var cy = h / 2;
            var lw = 3;
            ctx.lineWidth = lw;
            ctx.lineCap = "round";
            ctx.lineJoin = "round";

            var amplitude = Math.min(h / 4, (h - lw) / 2);
            var frequency = 0.08;
            var segW = 4;

            // Draw sine wave using lineTo segments (qml4j Context2D has no bezierCurveTo)
            function buildPath(fromX, toX) {
                var x = fromX;
                ctx.moveTo(x, cy + amplitude * Math.sin(x * frequency + phase));
                x += segW;
                while (x <= toX + segW) {
                    var px = x < toX ? x : toX;
                    ctx.lineTo(px, cy + amplitude * Math.sin(px * frequency + phase));
                    if (px >= toX) break;
                    x += segW;
                }
            }

            // Track (inactive, full width)
            ctx.beginPath();
            ctx.strokeStyle = trackColor;
            buildPath(0, w);
            ctx.stroke();

            // Indicator (active portion)
            ctx.beginPath();
            ctx.strokeStyle = activeColor;
            if (control.indeterminate) {
                var barW = w * 0.45;
                var startX = (phase / (Math.PI * 2)) * (w + barW) - barW;
                var fi = Math.max(0, startX);
                var ti = Math.min(w, startX + barW);
                if (fi < ti) {
                    buildPath(fi, ti);
                    ctx.stroke();
                }
            } else {
                var endX = w * Math.max(0, Math.min(1, progress));
                if (endX > 0) {
                    buildPath(0, endX);
                    ctx.stroke();
                }
            }
        }
        
        onPhaseChanged: requestPaint()
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }
}

