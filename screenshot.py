import subprocess
import time
import os
import xml.etree.ElementTree as ET
import re

ADB_PATH = "/home/sanjeev/Android/Sdk/platform-tools/adb"
DEVICE = "emulator-5554"
ARTIFACTS_DIR = "/home/sanjeev/Music"
APK_PATH = "app/build/outputs/apk/debug/app-debug.apk"

def run_adb(args):
    cmd = [ADB_PATH, "-s", DEVICE] + args
    res = subprocess.run(cmd, capture_output=True, text=True)
    return res.stdout.strip()

def wait_for_boot():
    print("Waiting for emulator to boot...")
    while True:
        try:
            boot_status = run_adb(["shell", "getprop", "sys.boot_completed"])
            if boot_status == "1":
                print("Emulator booted successfully!")
                break
        except Exception as e:
            pass
        time.sleep(2)

def capture_screenshot(filename):
    print(f"Capturing screenshot: {filename}")
    remote_path = f"/sdcard/{filename}"
    local_path = os.path.join(ARTIFACTS_DIR, filename)
    run_adb(["shell", "screencap", "-p", remote_path])
    run_adb(["pull", remote_path, local_path])
    run_adb(["shell", "rm", remote_path])
    print(f"Saved screenshot to {local_path}")

def get_element_bounds(target_attr_val):
    # Dump UI hierarchy
    run_adb(["shell", "uiautomator", "dump", "/sdcard/window_dump.xml"])
    dump_local = os.path.join(ARTIFACTS_DIR, "window_dump.xml")
    run_adb(["pull", "/sdcard/window_dump.xml", dump_local])
    run_adb(["shell", "rm", "/sdcard/window_dump.xml"])
    
    if not os.path.exists(dump_local):
        return None

    try:
        tree = ET.parse(dump_local)
        root = tree.getroot()
        for node in root.iter('node'):
            res_id = node.get('resource-id', '')
            text = node.get('text', '')
            desc = node.get('content-desc', '')
            
            # Match by suffix of resource-id, exact text, or description
            if (target_attr_val in res_id or 
                target_attr_val.lower() == text.lower() or 
                target_attr_val.lower() == desc.lower() or
                target_attr_val in text):
                bounds = node.get('bounds')
                match = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
                if match:
                    left, top, right, bottom = map(int, match.groups())
                    center_x = (left + right) // 2
                    center_y = (top + bottom) // 2
                    print(f"Found '{target_attr_val}' at ({center_x}, {center_y})")
                    os.remove(dump_local)
                    return center_x, center_y
    except Exception as e:
        print(f"Error parsing UI dump: {e}")
    
    if os.path.exists(dump_local):
        os.remove(dump_local)
    return None

def tap_element(target):
    coords = get_element_bounds(target)
    if coords:
        run_adb(["shell", "input", "tap", str(coords[0]), str(coords[1])])
        return True
    else:
        print(f"Could not find coordinates for: {target}")
        return False

def main():
    wait_for_boot()
    
    # 1. Install APK
    print("Installing APK...")
    subprocess.run([ADB_PATH, "-s", DEVICE, "install", "-r", APK_PATH])
    time.sleep(2)
    
    # 2. Launch Main Activity
    print("Launching MainActivity...")
    run_adb(["shell", "am", "start", "-n", "com.example.zenscan/.MainActivity"])
    time.sleep(4)  # Wait for startup animation / database load
    
    # 3. Capture Home View (Light Mode)
    capture_screenshot("home_light_view.png")
    
    # 4. Tap "Files" tab
    print("Navigating to Files Tab...")
    if not tap_element("nav_files"):
        # Fallback coordinate if UI dump fails (assuming typical screen aspect ratio 1080x2400)
        # Tab 2 (Files) is roughly center bottom
        run_adb(["shell", "input", "tap", "540", "2300"])
    time.sleep(2)
    capture_screenshot("files_light_view.png")
    
    # 5. Tap "Settings" tab
    print("Navigating to Settings Tab...")
    if not tap_element("nav_settings"):
        # Fallback tab 3 (Settings) is bottom right
        run_adb(["shell", "input", "tap", "900", "2300"])
    time.sleep(2)
    capture_screenshot("settings_light_view.png")
    
    # 6. Toggle Dark Theme
    print("Toggling Dark Theme...")
    if not tap_element("rbThemeDark"):
        # Fallback settings radio button tap
        run_adb(["shell", "input", "tap", "500", "500"])
    time.sleep(3)  # Wait for activity to recreate and apply theme
    capture_screenshot("settings_dark_view.png")
    
    # 7. Tap "Home" tab to see Home view in Dark Mode!
    print("Navigating to Home Tab (Dark Mode Check)...")
    if not tap_element("nav_home"):
        # Fallback tab 1 (Home) is bottom left
        run_adb(["shell", "input", "tap", "180", "2300"])
    time.sleep(2)
    capture_screenshot("home_dark_view.png")
    
    # 8. Tap "Files" tab in Dark Mode!
    print("Navigating to Files Tab (Dark Mode Check)...")
    if not tap_element("nav_files"):
        run_adb(["shell", "input", "tap", "540", "2300"])
    time.sleep(2)
    capture_screenshot("files_dark_view.png")
    
    print("All tasks finished successfully!")

if __name__ == "__main__":
    main()


