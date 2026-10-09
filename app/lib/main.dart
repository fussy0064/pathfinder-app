import 'package:flutter/material.dart';

import 'app_controller.dart';
import 'services/location_service.dart';
import 'ui/map_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const PathfinderApp());
}

class PathfinderApp extends StatefulWidget {
  const PathfinderApp({super.key});

  @override
  State<PathfinderApp> createState() => _PathfinderAppState();
}

class _PathfinderAppState extends State<PathfinderApp> {
  late final AppController _controller = AppController(LocationService());

  @override
  void initState() {
    super.initState();
    _controller.init();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Pathfinder',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF14506E),
        brightness: Brightness.light,
      ),
      home: MapScreen(controller: _controller),
    );
  }
}
