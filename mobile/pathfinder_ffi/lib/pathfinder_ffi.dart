/// Offline routing for Flutter. The heavy work runs in the C++ engine on a
/// background Isolate, so the UI never freezes.
library;

export 'src/engine.dart' show PathfinderEngine;
export 'src/models.dart';
