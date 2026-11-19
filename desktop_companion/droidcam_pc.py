#!/usr/bin/env python3
"""
DroidCam Alternative - Desktop Companion Virtual Webcam & Audio Client
"""

import argparse
import json
import socket
import struct
import subprocess
import time
from typing import Optional

import av
import cv2
import numpy as np

try:
    import pyvirtualcam
    HAS_VIRTUAL_CAM = True
except ImportError:
    HAS_VIRTUAL_CAM = False

try:
    import sounddevice as sd
    HAS_AUDIO = True
except ImportError:
    HAS_AUDIO = False


MAGIC = b"DCAM"
HEADER_SIZE = 17
TYPE_H264_NAL = 0x01
TYPE_MJPEG_FRAME = 0x02
TYPE_PCM_AUDIO = 0x03
TYPE_HEARTBEAT = 0x04
TYPE_STREAM_INFO = 0x05


def setup_adb_forwarding(http_port: int = 4747, tcp_port: int = 4748) -> bool:
    try:
        subprocess.run(["adb", "start-server"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        r1 = subprocess.run(["adb", "forward", f"tcp:{http_port}", f"tcp:{http_port}"], capture_output=True, text=True)
        r2 = subprocess.run(["adb", "forward", f"tcp:{tcp_port}", f"tcp:{tcp_port}"], capture_output=True, text=True)
        if r1.returncode == 0 and r2.returncode == 0:
            print(f"[USB/ADB] Port forwarding active: localhost:{http_port} & :{tcp_port} -> Android")
            return True
        print(f"[USB/ADB] Warning: {r1.stderr.strip() or r2.stderr.strip()}")
        return False
    except FileNotFoundError:
        print("[USB/ADB] Error: 'adb' command not found in PATH. Install Android Platform Tools.")
        return False


def recv_exact(sock: socket.socket, size: int) -> Optional[bytes]:
    buf = bytearray()
    while len(buf) < size:
        chunk = sock.recv(size - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)


class DroidCamDesktopClient:
    def __init__(self, host: str, tcp_port: int, http_port: int, show_preview: bool, enable_audio: bool):
        self.host = host
        self.tcp_port = tcp_port
        self.http_port = http_port
        self.show_preview = show_preview
        self.enable_audio = enable_audio and HAS_AUDIO

        self.running = True
        self.sock: Optional[socket.socket] = None
        self.codec_ctx = av.CodecContext.create("h264", "r")
        self.vcam: Optional["pyvirtualcam.Camera"] = None
        self.audio_stream = None
        self.stream_meta = {"width": 1280, "height": 720, "fps": 30, "audioSampleRate": 48000, "audioChannels": 1}

    def send_remote_command(self, action: str, value: Optional[int] = None):
        if not self.sock:
            return
        payload = {"action": action}
        if value is not None:
            payload["value"] = value
        try:
            self.sock.sendall((json.dumps(payload) + "\n").encode("utf-8"))
            print(f"[Control] Sent command: {payload}")
        except Exception as e:
            print(f"[Control] Failed to send command: {e}")

    def _ensure_virtual_cam(self, width: int, height: int, fps: int):
        if not HAS_VIRTUAL_CAM:
            return
        if self.vcam is not None and (self.vcam.width != width or self.vcam.height != height):
            self.vcam.close()
            self.vcam = None
        if self.vcam is None:
            try:
                self.vcam = pyvirtualcam.Camera(width=width, height=height, fps=fps, fmt=pyvirtualcam.PixelFormat.BGR)
                print(f"[VirtualCam] Outputting {width}x{height}@{fps}fps to '{self.vcam.device}' (Ready for Zoom/Meet/OBS)")
            except Exception as e:
                print(f"[VirtualCam] Could not open virtual camera driver: {e}")

    def _ensure_audio_out(self, sample_rate: int, channels: int):
        if not self.enable_audio:
            return
        if self.audio_stream is None:
            try:
                self.audio_stream = sd.RawOutputStream(
                    samplerate=sample_rate,
                    channels=channels,
                    dtype="int16",
                    blocksize=1024
                )
                self.audio_stream.start()
                print(f"[Audio] Live microphone audio playback active ({sample_rate} Hz, {channels} ch)")
            except Exception as e:
                print(f"[Audio] Audio output init failed: {e}")
                self.enable_audio = False

    def run_forever(self):
        while self.running:
            try:
                print(f"[Network] Connecting to {self.host}:{self.tcp_port} ...")
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                sock.settimeout(5.0)
                sock.connect((self.host, self.tcp_port))
                sock.settimeout(None)
                self.sock = sock
                self.codec_ctx = av.CodecContext.create("h264", "r")
                print("[Network] Connected! Streaming live video & audio.")
                print("          Hotkeys (in Preview Window): [C] Switch Cam | [F] Torch | [M] Mute | [Q] Quit")

                self._receive_loop(sock)
            except Exception as e:
                print(f"[Network] Disconnected ({e}). Reconnecting in 2 seconds...")
                time.sleep(2.0)
            finally:
                if self.sock:
                    try:
                        self.sock.close()
                    except Exception:
                        pass
                    self.sock = None

    def _receive_loop(self, sock: socket.socket):
        while self.running:
            header = recv_exact(sock, HEADER_SIZE)
            if not header:
                raise ConnectionError("EOF from Android server")

            magic, pkt_type, pts_us, payload_len = struct.unpack(">4sBqI", header)
            if magic != MAGIC:
                raise ValueError(f"Invalid packet magic: {magic}")

            payload = recv_exact(sock, payload_len) if payload_len > 0 else b""
            if payload is None:
                raise ConnectionError("Incomplete payload")

            if pkt_type == TYPE_STREAM_INFO:
                self.stream_meta = json.loads(payload.decode("utf-8"))
                print(f"[StreamInfo] {self.stream_meta}")
                self._ensure_audio_out(
                    self.stream_meta.get("audioSampleRate", 48000),
                    self.stream_meta.get("audioChannels", 1)
                )

            elif pkt_type == TYPE_H264_NAL:
                packet = av.Packet(payload)
                frames = self.codec_ctx.decode(packet)
                for frame in frames:
                    bgr = frame.to_ndarray(format="bgr24")
                    self._output_video_frame(bgr)

            elif pkt_type == TYPE_MJPEG_FRAME:
                arr = np.frombuffer(payload, dtype=np.uint8)
                bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
                if bgr is not None:
                    self._output_video_frame(bgr)

            elif pkt_type == TYPE_PCM_AUDIO:
                if self.audio_stream is not None and len(payload) > 0:
                    self.audio_stream.write(payload)

    def _output_video_frame(self, bgr: np.ndarray):
        h, w = bgr.shape[:2]
        self._ensure_virtual_cam(w, h, self.stream_meta.get("fps", 30))

        if self.vcam is not None:
            self.vcam.send(bgr)
            self.vcam.sleep_until_next_frame()

        if self.show_preview:
            cv2.imshow("DroidCam Alternative - PC Monitor (C:Cam, F:Flash, M:Mute, Q:Quit)", bgr)
            key = cv2.waitKey(1) & 0xFF
            if key == ord("q"):
                self.running = False
            elif key == ord("c"):
                self.send_remote_command("switch_camera")
            elif key == ord("f"):
                self.send_remote_command("toggle_torch")
            elif key == ord("m"):
                self.send_remote_command("toggle_mute")


def main():
    parser = argparse.ArgumentParser(description="DroidCam Alternative PC Virtual Webcam Client")
    parser.add_argument("--mode", choices=["wifi", "usb"], default="wifi", help="Connection mode: wifi or usb")
    parser.add_argument("--ip", default="192.168.1.100", help="Smartphone IP address (for Wi-Fi mode)")
    parser.add_argument("--http-port", type=int, default=4747, help="HTTP/MJPEG port (default: 4747)")
    parser.add_argument("--tcp-port", type=int, default=4748, help="Low-latency TCP stream port (default: 4748)")
    parser.add_argument("--no-preview", action="store_true", help="Disable local OpenCV preview window")
    parser.add_argument("--no-audio", action="store_true", help="Disable PC audio playback")
    args = parser.parse_args()

    target_host = args.ip
    if args.mode == "usb":
        setup_adb_forwarding(args.http_port, args.tcp_port)
        target_host = "127.0.0.1"

    client = DroidCamDesktopClient(
        host=target_host,
        tcp_port=args.tcp_port,
        http_port=args.http_port,
        show_preview=not args.no_preview,
        enable_audio=not args.no_audio
    )
    try:
        client.run_forever()
    except KeyboardInterrupt:
        print("\nStopping DroidCam Desktop Client...")
    finally:
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
