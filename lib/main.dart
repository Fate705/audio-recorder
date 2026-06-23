import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:intl/intl.dart';

void main() {
  runApp(const AudioCaptureApp());
}

class AudioCaptureApp extends StatelessWidget {
  const AudioCaptureApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Internal Audio Recorder',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF1A1A2E),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const RecorderScreen(),
    );
  }
}

class RecorderScreen extends StatefulWidget {
  const RecorderScreen({super.key});

  @override
  State<RecorderScreen> createState() => _RecorderScreenState();
}

class _RecorderScreenState extends State<RecorderScreen>
    with TickerProviderStateMixin {
  static const MethodChannel _channel =
      MethodChannel('com.audiocapture.recorder/audio');

  bool _isRecording = false;
  bool _isLoading = false;
  String _statusMessage = 'Siap merekam';
  String? _lastFilePath;
  Duration _recordDuration = Duration.zero;
  Timer? _timer;
  List<String> _logs = [];

  late AnimationController _pulseController;
  late Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1000),
    );
    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.15).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );
    _setupMethodCallHandler();
  }

  void _setupMethodCallHandler() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onRecordingStarted':
          setState(() {
            _isRecording = true;
            _isLoading = false;
            _statusMessage = 'Sedang merekam...';
          });
          _startTimer();
          _pulseController.repeat(reverse: true);
          _addLog('✅ Rekaman dimulai');
          break;
        case 'onRecordingStopped':
          final filePath = call.arguments as String?;
          setState(() {
            _isRecording = false;
            _isLoading = false;
            _lastFilePath = filePath;
            _statusMessage = filePath != null
                ? 'Tersimpan!'
                : 'Rekaman selesai';
          });
          _stopTimer();
          _pulseController.stop();
          _pulseController.reset();
          _addLog('💾 File: ${filePath ?? "tidak diketahui"}');
          break;
        case 'onError':
          final error = call.arguments as String?;
          setState(() {
            _isRecording = false;
            _isLoading = false;
            _statusMessage = 'Error: $error';
          });
          _stopTimer();
          _pulseController.stop();
          _pulseController.reset();
          _addLog('❌ Error: $error');
          _showError(error ?? 'Terjadi kesalahan');
          break;
      }
    });
  }

  void _addLog(String msg) {
    final time = DateFormat('HH:mm:ss').format(DateTime.now());
    setState(() {
      _logs.insert(0, '[$time] $msg');
      if (_logs.length > 50) _logs.removeLast();
    });
  }

  void _startTimer() {
    _recordDuration = Duration.zero;
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      setState(() {
        _recordDuration += const Duration(seconds: 1);
      });
    });
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
  }

  String get _durationText {
    final h = _recordDuration.inHours.toString().padLeft(2, '0');
    final m = (_recordDuration.inMinutes % 60).toString().padLeft(2, '0');
    final s = (_recordDuration.inSeconds % 60).toString().padLeft(2, '0');
    return '$h:$m:$s';
  }

  Future<bool> _requestPermissions() async {
    final statuses = await [
      Permission.microphone,
      Permission.storage,
      Permission.manageExternalStorage,
    ].request();

    final micGranted = statuses[Permission.microphone]?.isGranted ?? false;
    if (!micGranted) {
      _showError('Izin mikrofon diperlukan!');
      return false;
    }
    return true;
  }

  Future<void> _startRecording() async {
    if (_isLoading || _isRecording) return;

    setState(() {
      _isLoading = true;
      _statusMessage = 'Meminta izin MediaProjection...';
    });
    _addLog('🎙️ Memulai proses rekaman...');

    final permitted = await _requestPermissions();
    if (!permitted) {
      setState(() {
        _isLoading = false;
        _statusMessage = 'Izin ditolak';
      });
      return;
    }

    try {
      await _channel.invokeMethod('startRecording');
    } on PlatformException catch (e) {
      setState(() {
        _isLoading = false;
        _statusMessage = 'Gagal: ${e.message}';
      });
      _addLog('❌ ${e.message}');
    }
  }

  Future<void> _stopRecording() async {
    if (!_isRecording) return;
    setState(() {
      _isLoading = true;
      _statusMessage = 'Menyimpan file...';
    });
    _addLog('⏹️ Menghentikan rekaman...');
    try {
      await _channel.invokeMethod('stopRecording');
    } on PlatformException catch (e) {
      setState(() {
        _isLoading = false;
      });
      _addLog('❌ ${e.message}');
    }
  }

  void _showError(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(msg),
        backgroundColor: Colors.red.shade800,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  @override
  void dispose() {
    _timer?.cancel();
    _pulseController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0D0D1A),
      appBar: AppBar(
        backgroundColor: const Color(0xFF1A1A2E),
        title: const Row(
          children: [
            Icon(Icons.graphic_eq, color: Color(0xFF00D4FF)),
            SizedBox(width: 8),
            Text(
              'Internal Audio Recorder',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                fontSize: 16,
              ),
            ),
          ],
        ),
        elevation: 0,
      ),
      body: Column(
        children: [
          _buildStatusBanner(),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                children: [
                  _buildRecordButton(),
                  const SizedBox(height: 32),
                  if (_lastFilePath != null) _buildFileInfo(),
                  const SizedBox(height: 16),
                  _buildInfoCard(),
                  const SizedBox(height: 16),
                  _buildLogPanel(),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildStatusBanner() {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 20),
      color: _isRecording
          ? const Color(0xFF8B0000)
          : const Color(0xFF16213E),
      child: Row(
        children: [
          if (_isRecording)
            AnimatedBuilder(
              animation: _pulseAnimation,
              builder: (_, child) => Transform.scale(
                scale: _pulseAnimation.value,
                child: child,
              ),
              child: Container(
                width: 10,
                height: 10,
                decoration: const BoxDecoration(
                  color: Colors.red,
                  shape: BoxShape.circle,
                ),
              ),
            )
          else
            const Icon(Icons.radio_button_unchecked,
                color: Colors.grey, size: 10),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              _isRecording
                  ? 'MEREKAM  $_durationText'
                  : _statusMessage,
              style: TextStyle(
                color: _isRecording ? Colors.red.shade200 : Colors.grey,
                fontWeight: FontWeight.w600,
                letterSpacing: 1.2,
                fontSize: 13,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRecordButton() {
    return Column(
      children: [
        const SizedBox(height: 16),
        AnimatedBuilder(
          animation: _pulseAnimation,
          builder: (_, child) => Transform.scale(
            scale: _isRecording ? _pulseAnimation.value : 1.0,
            child: child,
          ),
          child: GestureDetector(
            onTap: _isLoading
                ? null
                : (_isRecording ? _stopRecording : _startRecording),
            child: Container(
              width: 160,
              height: 160,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: _isRecording
                      ? [const Color(0xFFFF4444), const Color(0xFF8B0000)]
                      : [const Color(0xFF00D4FF), const Color(0xFF0066CC)],
                ),
                boxShadow: [
                  BoxShadow(
                    color: _isRecording
                        ? Colors.red.withOpacity(0.5)
                        : const Color(0xFF00D4FF).withOpacity(0.4),
                    blurRadius: 30,
                    spreadRadius: 5,
                  ),
                ],
              ),
              child: _isLoading
                  ? const Center(
                      child: CircularProgressIndicator(color: Colors.white),
                    )
                  : Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          _isRecording ? Icons.stop : Icons.mic,
                          color: Colors.white,
                          size: 48,
                        ),
                        const SizedBox(height: 6),
                        Text(
                          _isRecording ? 'BERHENTI' : 'REKAM',
                          style: const TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.bold,
                            fontSize: 13,
                            letterSpacing: 2,
                          ),
                        ),
                      ],
                    ),
            ),
          ),
        ),
        const SizedBox(height: 20),
        Text(
          _isRecording
              ? 'Merekam Audio Internal + Mic'
              : 'Tap untuk mulai merekam\nAudio Internal (Discord/Game) + Mic',
          textAlign: TextAlign.center,
          style: TextStyle(
            color: Colors.grey.shade400,
            fontSize: 13,
            height: 1.5,
          ),
        ),
        if (_isRecording) ...[
          const SizedBox(height: 12),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              _buildSourceChip(Icons.speaker, 'Audio Internal'),
              const SizedBox(width: 8),
              _buildSourceChip(Icons.mic, 'Mikrofon'),
            ],
          ),
        ],
      ],
    );
  }

  Widget _buildSourceChip(IconData icon, String label) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: Colors.green.withOpacity(0.2),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.green.shade700),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 12, color: Colors.green.shade300),
          const SizedBox(width: 4),
          Text(
            label,
            style: TextStyle(
              color: Colors.green.shade300,
              fontSize: 11,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFileInfo() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF1A2744),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFF00D4FF).withOpacity(0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.audio_file, color: Color(0xFF00D4FF), size: 18),
              const SizedBox(width: 8),
              Text(
                'File Terakhir',
                style: TextStyle(
                  color: Colors.grey.shade300,
                  fontWeight: FontWeight.bold,
                  fontSize: 13,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            _lastFilePath ?? '-',
            style: const TextStyle(
              color: Color(0xFF00D4FF),
              fontSize: 11,
              fontFamily: 'monospace',
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoCard() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: const Color(0xFF16213E),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.info_outline, color: Colors.amber, size: 16),
              SizedBox(width: 6),
              Text(
                'Cara Kerja',
                style: TextStyle(
                  color: Colors.amber,
                  fontWeight: FontWeight.bold,
                  fontSize: 13,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          _buildInfoRow('MediaProjection API',
              'Menangkap audio internal (Discord/Game)'),
          _buildInfoRow('AudioPlaybackCapture',
              'Izin khusus rekam audio aplikasi lain (API 29+)'),
          _buildInfoRow('AudioRecord API',
              'Rekam suara mikrofon secara paralel'),
          _buildInfoRow('Foreground Service',
              'Berjalan stabil saat layar mati'),
          _buildInfoRow('Output Format',
              'File .m4a di folder Music/AudioCapture'),
        ],
      ),
    );
  }

  Widget _buildInfoRow(String title, String desc) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('• ', style: TextStyle(color: Color(0xFF00D4FF))),
          Expanded(
            child: RichText(
              text: TextSpan(
                children: [
                  TextSpan(
                    text: '$title: ',
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.w600,
                      fontSize: 12,
                    ),
                  ),
                  TextSpan(
                    text: desc,
                    style: TextStyle(
                      color: Colors.grey.shade400,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLogPanel() {
    if (_logs.isEmpty) return const SizedBox.shrink();
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF0A0A1A),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: Colors.grey.shade800),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'LOG',
            style: TextStyle(
              color: Colors.grey.shade500,
              fontSize: 10,
              letterSpacing: 2,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 8),
          ..._logs.take(10).map((log) => Padding(
                padding: const EdgeInsets.symmetric(vertical: 2),
                child: Text(
                  log,
                  style: const TextStyle(
                    color: Color(0xFF00FF88),
                    fontSize: 11,
                    fontFamily: 'monospace',
                  ),
                ),
              )),
        ],
      ),
    );
  }
}
