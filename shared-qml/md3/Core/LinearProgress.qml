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

        // Animation for phase shift (make it flow). Runs for determinate too so the
        // wave visibly flows and the Canvas keeps repainting (onPhaseChanged), instead
        // of freezing on the first paint with only the track drawn.
        NumberAnimation on phase {
            running: control.wavy && control.visible
            from: 0
            to: Math.PI * 2
            duration: 1000 // 1Hz wave frequency
            loops: Animation.Infinite
        }

        onPaint: {
            var ctx = getContext("2d");
            ctx.reset();

            var w = width;
            var h = height;
            var cy = h / 2;
            var lw = 4;
            ctx.lineWidth = lw;
            ctx.lineCap = "round";
            ctx.lineJoin = "round";
            // Inset by half the stroke so the round end-caps fall inside the canvas
            // instead of being clipped; clamp amplitude so peaks+caps stay in bounds.
            var m = lw / 2 + 1;
            var x0 = m, x1 = w - m;
            var amplitude = Math.min(h / 4, h / 2 - lw / 2);
            var frequency = 0.1;

            // Build a smooth sine-wave path using cubic Hermite bezier segments.
            // Each segment uses the analytical slope (A·k·cos(kx+φ)) as the tangent,
            // so the curve is mathematically C¹-continuous with no polyline kinks.
            // segW=8px gives ~8 bezier spans per wave period (≈63px) — fast and
            // indistinguishable from the true sine at any zoom level.
            function buildPath(fromX, toX) {
                var segW = 8;
                var x = fromX;
                ctx.moveTo(x, cy + amplitude * Math.sin(x * frequency + phase));
                while (x < toX) {
                    var x2 = Math.min(x + segW, toX);
                    var y1 = cy + amplitude * Math.sin(x  * frequency + phase);
                    var y2 = cy + amplitude * Math.sin(x2 * frequency + phase);
                    // Hermite control points from the analytical derivative.
                    var s1 = amplitude * frequency * Math.cos(x  * frequency + phase);
                    var s2 = amplitude * frequency * Math.cos(x2 * frequency + phase);
                    var d  = (x2 - x) / 3;
                    ctx.bezierCurveTo(x  + d, y1 + s1 * d,
                                      x2 - d, y2 - s2 * d,
                                      x2, y2);
                    x = x2;
                }
            }

            // Track (inactive, full width)
            ctx.beginPath();
            ctx.strokeStyle = trackColor;
            buildPath(x0, x1);
            ctx.stroke();

            // Indicator (active)
            ctx.beginPath();
            ctx.strokeStyle = activeColor;
            if (control.indeterminate) {
                var span = x1 - x0;
                var barWidth = span * 0.5;
                var startX = x0 + (phase / (Math.PI * 2)) * (span + barWidth) - barWidth;
                var fromXi = Math.max(x0, startX);
                var toXi   = Math.min(x1, startX + barWidth);
                if (fromXi < toXi) {
                    buildPath(fromXi, toXi);
                    ctx.stroke();
                }
            } else {
                var endX = x0 + (x1 - x0) * Math.max(0, Math.min(1, progress));
                if (endX > x0) {
                    buildPath(x0, endX);
                    ctx.stroke();
                }
            }
        }
        
        onPhaseChanged: requestPaint()
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
    }
}

