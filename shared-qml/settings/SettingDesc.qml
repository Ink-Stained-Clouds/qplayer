import QtQuick
import QtQuick.Layouts
import md3.Core

// A settings row's secondary line; collapses when there's nothing to say.
Text {
    Layout.fillWidth: true
    visible: text.length > 0
    color: Theme.color.onSurfaceVariantColor
    font.family: Theme.typography.bodySmall.family
    font.pixelSize: Theme.typography.bodySmall.size
    wrapMode: Text.WordWrap
}
