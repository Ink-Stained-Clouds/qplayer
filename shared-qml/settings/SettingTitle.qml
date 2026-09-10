import QtQuick
import QtQuick.Layouts
import md3.Core

// A settings row's main label. Elides rather than growing into whatever control
// shares its row: a translated title is routinely much longer than the original.
Text {
    Layout.fillWidth: true
    elide: Text.ElideRight
    color: Theme.color.onSurfaceColor
    font.family: Theme.typography.bodyLarge.family
    font.pixelSize: Theme.typography.bodyLarge.size
}
