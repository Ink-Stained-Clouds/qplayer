# AMLL Core Mesh Gradient

The Fluid lyric background contains a Java/Skija and SkSL port of the Mesh
Gradient renderer from @applemusic-like-lyrics/core.

- Upstream project: https://github.com/amll-dev/applemusic-like-lyrics
- Upstream package: packages/core
- Upstream license: GNU Affero General Public License v3.0 only
- Upstream copyright: Copyright (c) 2022-2024 AMLL Contributors

Ported source areas:

- src/bg-render/mesh-renderer/index.ts
- src/bg-render/mesh-renderer/cp-presets.ts
- src/bg-render/mesh-renderer/cp-generate.ts
- src/bg-render/mesh-renderer/mesh.vert.glsl
- src/bg-render/mesh-renderer/mesh.frag.glsl

QPlayer modifications made in 2026:

- TypeScript/WebGL was rewritten as Java 21 with Skija drawVertices.
- GLSL was rewritten as a SkSL runtime-effect resource.
- Control-point presets were moved into a data resource.
- Album transitions and static-frame caching were integrated with QPlayer's
  shared desktop/Android lyric compositor.

The complete AGPL-3.0-only license is included beside this notice as LICENSE.
