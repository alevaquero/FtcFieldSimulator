import socket
import time
import math
import random

# --- Configuration ---
SIMULATOR_IP = "127.0.0.1"
PLOT_LISTENER_PORT = 7778
SEND_INTERVAL_SECONDS = 0.1  # For continuous demo

# --- Global State for Demo ---
robot_opmode_start_time_ms = 0
last_sine_y = 50.0 # Demo state, can be kept or made local to demo
last_ramp_y = 10.0 # Demo state
ramp_increment = 2.0 # Demo state

# --- Socket Setup (global for this script) ---
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)


# --- Helper Functions for Time and Input ---
def get_current_plot_time_ms():
    """Gets a plot time relative to opmode start, or absolute if demo not started."""
    global robot_opmode_start_time_ms
    if robot_opmode_start_time_ms == 0:
        return int(time.time() * 1000)
    else:
        return robot_opmode_start_time_ms + int((time.time() - (robot_opmode_start_time_ms / 1000.0)) * 1000)

def get_float_input(prompt, default_value=None):
    while True:
        try:
            value_str = input(prompt)
            if not value_str and default_value is not None:
                print(f"No input, using default: {default_value}")
                return default_value
            return float(value_str)
        except ValueError:
            print("Invalid input. Please enter a valid number.")
        except Exception as e:
            print(f"An unexpected error occurred during input: {e}")
            return None

def get_int_input(prompt, default_value=None):
    while True:
        try:
            value_str = input(prompt)
            if not value_str and default_value is not None:
                print(f"No input, using default: {default_value}")
                return default_value
            return int(value_str)
        except ValueError:
            print("Invalid input. Please enter a valid integer.")
        except Exception as e:
            print(f"An unexpected error occurred during input: {e}")
            return None

def get_string_input(prompt, default_value=None):
    value_str = input(prompt)
    if not value_str and default_value is not None:
        print(f"No input, using default: {default_value}")
        return default_value
    return value_str

# --- Direct Message Sending Function ---
def send_message_direct(full_message_string):
    """Sends a pre-formatted string directly."""
    sock.sendto(full_message_string.encode('utf-8'), (SIMULATOR_IP, PLOT_LISTENER_PORT))
    print(f"Sent: {full_message_string}")


# --- Individual Message Senders for Menu ---
def send_test_point_y():
    print("\n--- Send Test Point Y ---")
    y = get_float_input("Enter Y value (e.g., 50.0, Enter for 50.0): ", 50.0)
    if y is None: return
    style = get_int_input("Enter style (1-5, Enter for 1): ", 1)
    if style is None: return
    if not (1 <= style <= 5):
        print("Invalid style. Defaulting to 1.")
        style = 1
    plot_time = get_current_plot_time_ms()
    full_msg = f"{plot_time},point_y:{y:.3f},{style}"
    send_message_direct(full_msg)

def send_test_line_y():
    print("\n--- Send Test Line Y ---")
    y = get_float_input("Enter Y value (e.g., 60.0, Enter for 60.0): ", 60.0)
    if y is None: return
    style = get_int_input("Enter style (1-10, Enter for 1): ", 1)
    if style is None: return
    if not (1 <= style <= 10):
        print("Invalid style. Defaulting to 1.")
        style = 1
    plot_time = get_current_plot_time_ms()
    full_msg = f"{plot_time},line_y:{y:.3f},{style}"
    send_message_direct(full_msg)

def send_test_text_marker():
    print("\n--- Send Test Text Marker ---")
    text_content = get_string_input("Enter marker text (e.g., \"Phase End\", Enter for 'Default Marker'): ", "Default Marker")
    position_keyword = ""
    while position_keyword not in ["top", "mid", "bot"]:
        position_keyword = get_string_input("Enter position ('top', 'mid', 'bot', Enter for 'mid'): ", "mid").lower().strip()
    plot_time = get_current_plot_time_ms()
    full_msg = f"{plot_time},text:{text_content},{position_keyword}"
    send_message_direct(full_msg)

# +++ NEW: Function to send key_value pair +++
def send_test_key_value():
    print("\n--- Send Test Key-Value ---")
    key = get_string_input("Enter Key (e.g., RobotState, Enter for 'Status'): ", "Status")
    value = get_string_input("Enter Value (e.g., Running, Enter for 'Idle'): ", "Idle")

    plot_time = get_current_plot_time_ms()
    # Format: <timestamp>,key_value:<key_string>,<value_string>
    full_msg = f"{plot_time},key_value:{key},{value}"
    send_message_direct(full_msg)
# +++++++++++++++++++++++++++++++++++++++++

def send_test_set_y_limits():
    print("\n--- Send Test Set Y Limits ---")
    min_y = get_float_input("Enter Min Y (e.g., 0.0, Enter for 0.0): ", 0.0)
    if min_y is None: return
    max_y = get_float_input(f"Enter Max Y (e.g., 100.0, > {min_y:.2f}, Enter for 100.0): ", 100.0)
    if max_y is None: return
    if max_y <= min_y:
        print(f"Max Y must be > Min Y. Adjusting to defaults.")
        min_y = 0.0
        max_y = 100.0
    plot_time = get_current_plot_time_ms()
    full_msg = f"{plot_time},set_y_limits:{max_y:.2f},{min_y:.2f}"
    send_message_direct(full_msg)

def send_test_set_y_units():
    print("\n--- Send Test Set Y Units ---")
    units = get_string_input("Enter Y units string (e.g., Volts, Enter for 'Units'): ", "Units")
    plot_time = get_current_plot_time_ms()
    full_msg = f"{plot_time},set_y_units:{units}"
    send_message_direct(full_msg)


# --- Continuous Demo Function ---
def run_continuous_demo(duration_seconds):
    global robot_opmode_start_time_ms, last_sine_y, last_ramp_y, ramp_increment
    print(f"\nRunning continuous demo for {duration_seconds} seconds...")
    if robot_opmode_start_time_ms == 0:
        robot_opmode_start_time_ms = int(time.time() * 1000)
        print(f"Simulated OpMode started at: {robot_opmode_start_time_ms} ms")

    # Reset demo-specific state
    last_sine_y = 50.0
    last_ramp_y = 10.0
    ramp_increment = 2.0
    script_start_time = time.time()

    current_time_for_setup = get_current_plot_time_ms()
    send_message_direct(f"{current_time_for_setup},set_y_limits:100.00,0.00")
    time.sleep(0.01)
    send_message_direct(f"{current_time_for_setup + 1},set_y_units:DemoValue")
    time.sleep(0.01)

    initial_marker_time = robot_opmode_start_time_ms + 1000
    send_message_direct(f"{initial_marker_time},text:Demo Started,top")
    time.sleep(0.01)
    # +++ Add initial key_value for demo +++
    send_message_direct(f"{robot_opmode_start_time_ms + 50},key_value:Mode,Initializing")
    send_message_direct(f"{robot_opmode_start_time_ms + 100},key_value:Target,Acquiring")
    # +++++++++++++++++++++++++++++++++++++

    count = 0
    sine_freq_hz = 0.2
    point_freq_hz = 0.3
    robot_mode = "Autonomous"

    while (time.time() - script_start_time) < duration_seconds:
        current_plot_time = get_current_plot_time_ms()
        elapsed_opmode_seconds = (current_plot_time - robot_opmode_start_time_ms) / 1000.0

        # Sine wave
        y_sine = 50 + 40 * math.sin(2 * math.pi * sine_freq_hz * elapsed_opmode_seconds)
        send_message_direct(f"{current_plot_time},line_y:{y_sine:.3f},1")
        last_sine_y = y_sine

        # Points
        if count % 2 == 0:
            y_points = 30 + 25 * math.cos(2 * math.pi * point_freq_hz * elapsed_opmode_seconds)
            send_message_direct(f"{current_plot_time + 1},point_y:{y_points:.3f},3")

        # Ramp
        if count % 3 == 0:
            last_ramp_y += ramp_increment
            if last_ramp_y > 95.0 or last_ramp_y < 5.0:
                ramp_increment *= -1
            send_message_direct(f"{current_plot_time + 2},line_y:{last_ramp_y:.3f},6")

        # +++ Send key_value pairs periodically in demo +++
        if count % 20 == 0: # Every 2 seconds approx (20 * 0.1s)
            send_message_direct(f"{current_plot_time},key_value:LoopCount,{count}")
            send_message_direct(f"{current_plot_time + 5},key_value:Battery,{90 - count*0.1:.1f}%")

        if count == 70: # Approx 7s
            robot_mode = "TeleOp"
            send_message_direct(f"{current_plot_time},key_value:Mode,{robot_mode}")
            send_message_direct(f"{current_plot_time + 10},key_value:Target,Player Input")

        if count == 130: # Approx 13s
             send_message_direct(f"{current_plot_time},key_value:Mode,EndGame")
        # +++++++++++++++++++++++++++++++++++++++++++++++

        # Text Markers
        if count == 50:
            event_marker_body = f"text:Event @ {elapsed_opmode_seconds:.1f}s,mid"
            send_message_direct(f"{current_plot_time},{event_marker_body}")
        if count == 100:
            event_marker_body = f"text:Checkpoint!,bot"
            send_message_direct(f"{current_plot_time},{event_marker_body}")

        time.sleep(SEND_INTERVAL_SECONDS)
        count += 1
    print("Continuous demo finished.")

# --- Main Menu Logic ---
def main_menu():
    global robot_opmode_start_time_ms
    print("\n--- Plotter Test Menu ---")
    print("Make sure FtcFieldSimulatorApp is running and plot window is open.")

    while True:
        print("\nChoose an action:")
        print("1. Send test point_y")
        print("2. Send test line_y")
        print("3. Send test text_marker")
        print("4. Send test key_value") # +++ NEW MENU OPTION +++
        print("5. Send test set_y_limits")
        print("6. Send test set_y_units")
        print("-------------------------")
        print("7. Run continuous demo (15 seconds)")
        print("8. Run continuous demo (60 seconds)")
        print("9. Reset/Start OpMode Timer")
        print("-------------------------")
        print("0. Exit")

        choice = input("Enter choice: ")

        if choice == '1':
            send_test_point_y()
        elif choice == '2':
            send_test_line_y()
        elif choice == '3':
            send_test_text_marker()
        elif choice == '4': # +++ Call new function +++
            send_test_key_value()
        elif choice == '5':
            send_test_set_y_limits()
        elif choice == '6':
            send_test_set_y_units()
        elif choice == '7':
            run_continuous_demo(15)
        elif choice == '8':
            run_continuous_demo(60)
        elif choice == '9':
            robot_opmode_start_time_ms = int(time.time() * 1000)
            print(f"OpMode timer reset/started. Current base time: {robot_opmode_start_time_ms} ms")
        elif choice == '0':
            print("Exiting.")
            break
        else:
            print("Invalid choice. Please try again.")
        time.sleep(0.1) # Small delay

if __name__ == "__main__":
    try:
        main_menu()
    except KeyboardInterrupt:
        print("\nExiting due to user interrupt.")
    except Exception as e:
        print(f"An error occurred in main: {e}")
        import traceback
        traceback.print_exc()
    finally:
        print("Closing UDP socket.")
        sock.close()


# import socket
# import time
# import math
# import random
#
# # --- Configuration ---
# SIMULATOR_IP = "127.0.0.1"
# PLOT_LISTENER_PORT = 7778
# SEND_INTERVAL_SECONDS = 0.1  # For continuous demo
#
# # --- Global State for Demo ---
# robot_opmode_start_time_ms = 0
# # Demo-specific state (can be kept or removed if demo is simplified further)
# last_sine_y = 50.0
# last_ramp_y = 10.0
# ramp_increment = 2.0
#
# # --- Socket Setup (global for this script) ---
# sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
#
#
# # --- Helper Functions for Time and Input ---
# def get_current_plot_time_ms():
#     """Gets a plot time relative to opmode start, or absolute if demo not started."""
#     global robot_opmode_start_time_ms
#     if robot_opmode_start_time_ms == 0:
#         return int(time.time() * 1000)  # Use current wall clock if demo not running
#     else:
#         # Time since the simulated opmode started
#         return robot_opmode_start_time_ms + int((time.time() - (robot_opmode_start_time_ms / 1000.0)) * 1000)
#
# def get_float_input(prompt, default_value=None):
#     while True:
#         try:
#             value_str = input(prompt)
#             if not value_str and default_value is not None:
#                 print(f"No input, using default: {default_value}")
#                 return default_value
#             return float(value_str)
#         except ValueError:
#             print("Invalid input. Please enter a valid number.")
#         except Exception as e:
#             print(f"An unexpected error occurred during input: {e}")
#             return None
#
# def get_int_input(prompt, default_value=None):
#     while True:
#         try:
#             value_str = input(prompt)
#             if not value_str and default_value is not None:
#                 print(f"No input, using default: {default_value}")
#                 return default_value
#             return int(value_str)
#         except ValueError:
#             print("Invalid input. Please enter a valid integer.")
#         except Exception as e:
#             print(f"An unexpected error occurred during input: {e}")
#             return None
#
# # --- Direct Message Sending Function ---
# def send_message_direct(full_message_string):
#     """Sends a pre-formatted string directly."""
#     sock.sendto(full_message_string.encode('utf-8'), (SIMULATOR_IP, PLOT_LISTENER_PORT))
#     print(f"Sent: {full_message_string}")
#
#
# # --- Individual Message Senders for Menu ---
# def send_test_point_y():
#     print("\n--- Send Test Point Y ---")
#     y = get_float_input("Enter Y value (e.g., 50.0, press Enter for 50.0): ", 50.0)
#     if y is None: return
#
#     style = get_int_input("Enter style (1-5, e.g., 1, press Enter for 1): ", 1)
#     if style is None: return
#     if not (1 <= style <= 5):  # Assuming 5 point styles
#         print("Invalid style for point_y. Defaulting to 1.")
#         style = 1
#
#     plot_time = get_current_plot_time_ms()
#     # Format: <timestamp>,point_y:<y_value>,<style>
#     full_msg = f"{plot_time},point_y:{y:.3f},{style}"
#     send_message_direct(full_msg)
#
# def send_test_line_y():
#     print("\n--- Send Test Line Y ---")
#     y = get_float_input("Enter Y value (e.g., 60.0, press Enter for 60.0): ", 60.0)
#     if y is None: return
#
#     style = get_int_input("Enter style (1-10, e.g., 1, press Enter for 1): ", 1)
#     if style is None: return
#     if not (1 <= style <= 10):  # Assuming 10 line styles
#         print("Invalid style for line_y. Defaulting to 1.")
#         style = 1
#
#     plot_time = get_current_plot_time_ms()
#     # Format: <timestamp>,line_y:<y_value>,<style>
#     full_msg = f"{plot_time},line_y:{y:.3f},{style}"
#     send_message_direct(full_msg)
#
# def send_test_text_marker():
#     print("\n--- Send Test Text Marker ---")
#     text_content = input("Enter marker text (use \"quotes\" for commas, e.g., \"Phase End\"): ")
#     if not text_content:
#         text_content = "Default Marker"
#
#     position_keyword = ""
#     while position_keyword not in ["top", "mid", "bot"]:
#         position_keyword = input("Enter position ('top', 'mid', 'bot', Enter for 'mid'): ").lower().strip()
#         if not position_keyword:
#             position_keyword = "mid"
#             print("No input, using default: mid")
#             break # Exit loop if default is used
#
#     plot_time = get_current_plot_time_ms()
#     # Format: <timestamp>,text:<text_string>,<position_keyword>
#     full_msg = f"{plot_time},text:{text_content},{position_keyword}"
#     send_message_direct(full_msg)
#
# def send_test_set_y_limits():
#     print("\n--- Send Test Set Y Limits ---")
#     min_y = get_float_input("Enter Min Y (e.g., 0.0, press Enter for 0.0): ", 0.0)
#     if min_y is None: return
#
#     max_y = get_float_input(f"Enter Max Y (e.g., 100.0, must be > {min_y:.2f}, press Enter for 100.0): ", 100.0)
#     if max_y is None: return
#
#     if max_y <= min_y:
#         print(f"Max Y ({max_y:.2f}) must be greater than Min Y ({min_y:.2f}). Adjusting to defaults.")
#         min_y = 0.0
#         max_y = 100.0
#
#     plot_time = get_current_plot_time_ms()
#     # Format: <timestamp>,set_y_limits:<max_y>,<min_y>
#     full_msg = f"{plot_time},set_y_limits:{max_y:.2f},{min_y:.2f}"
#     send_message_direct(full_msg)
#
# def send_test_set_y_units():
#     print("\n--- Send Test Set Y Units ---")
#     units = input("Enter Y units string (e.g., Volts, press Enter for 'Units'): ")
#     if not units:
#         units = "Units" # Default if nothing entered
#
#     plot_time = get_current_plot_time_ms()
#     # Format: <timestamp>,set_y_units:<unit_string>
#     full_msg = f"{plot_time},set_y_units:{units}"
#     send_message_direct(full_msg)
#
#
# # --- Continuous Demo Function ---
# def run_continuous_demo(duration_seconds):
#     global robot_opmode_start_time_ms, last_sine_y, last_ramp_y, ramp_increment
#     print(f"\nRunning continuous demo for {duration_seconds} seconds...")
#     if robot_opmode_start_time_ms == 0:
#         robot_opmode_start_time_ms = int(time.time() * 1000)
#         print(f"Simulated OpMode started at: {robot_opmode_start_time_ms} ms")
#
#     # Reset demo-specific state if needed, or make them local to the function
#     last_sine_y = 50.0
#     last_ramp_y = 10.0
#     ramp_increment = 2.0
#     script_start_time = time.time()
#
#     # Initial messages
#     current_time_for_setup = get_current_plot_time_ms() # Use a consistent time for these setup messages
#     send_message_direct(f"{current_time_for_setup},set_y_limits:100.00,0.00")
#     time.sleep(0.01) # Small delay
#     send_message_direct(f"{current_time_for_setup + 1},set_y_units:DemoValue") # Slightly offset time
#     time.sleep(0.01)
#
#     initial_marker_time = robot_opmode_start_time_ms + 1000 # 1s into demo
#     send_message_direct(f"{initial_marker_time},text:Demo Started,top")
#
#     count = 0
#     sine_freq_hz = 0.2
#     point_freq_hz = 0.3
#
#     while (time.time() - script_start_time) < duration_seconds:
#         current_plot_time = get_current_plot_time_ms()
#         elapsed_opmode_seconds = (current_plot_time - robot_opmode_start_time_ms) / 1000.0
#
#         # Sine wave
#         y_sine = 50 + 40 * math.sin(2 * math.pi * sine_freq_hz * elapsed_opmode_seconds)
#         send_message_direct(f"{current_plot_time},line_y:{y_sine:.3f},1") # Style 1
#         last_sine_y = y_sine
#
#         # Points
#         if count % 2 == 0:
#             y_points = 30 + 25 * math.cos(2 * math.pi * point_freq_hz * elapsed_opmode_seconds)
#             send_message_direct(f"{current_plot_time + 1},point_y:{y_points:.3f},3") # Style 3
#
#         # Ramp
#         if count % 3 == 0:
#             last_ramp_y += ramp_increment
#             if last_ramp_y > 95.0 or last_ramp_y < 5.0:
#                 ramp_increment *= -1
#             send_message_direct(f"{current_plot_time + 2},line_y:{last_ramp_y:.3f},6") # Style 6
#
#         # Occasional Text Marker
#         if count == 50:  # Approx 5s
#             event_marker_body = f"text:Event @ {elapsed_opmode_seconds:.1f}s,mid"
#             send_message_direct(f"{current_plot_time},{event_marker_body}")
#
#         if count == 100:  # Approx 10s
#             event_marker_body = f"text:Checkpoint!,bot"
#             send_message_direct(f"{current_plot_time},{event_marker_body}")
#
#         time.sleep(SEND_INTERVAL_SECONDS)
#         count += 1
#     print("Continuous demo finished.")
#
# # --- Main Menu Logic ---
# def main_menu():
#     global robot_opmode_start_time_ms
#     print("\n--- Plotter Test Menu ---")
#     print("Make sure FtcFieldSimulatorApp is running and plot window is open.")
#
#     while True:
#         print("\nChoose an action:")
#         print("1. Send test point_y")
#         print("2. Send test line_y")
#         print("3. Send test text_marker (vertical line annotation)")
#         print("4. Send test set_y_limits")
#         print("5. Send test set_y_units")
#         print("-------------------------")
#         print("6. Run continuous demo (15 seconds)")
#         print("7. Run continuous demo (60 seconds)")
#         print("8. Reset/Start OpMode Timer (for relative timestamps in demo)")
#         print("-------------------------")
#         print("0. Exit")
#
#         choice = input("Enter choice: ")
#
#         if choice == '1':
#             send_test_point_y()
#         elif choice == '2':
#             send_test_line_y()
#         elif choice == '3':
#             send_test_text_marker()
#         elif choice == '4':
#             send_test_set_y_limits()
#         elif choice == '5':
#             send_test_set_y_units()
#         elif choice == '6':
#             run_continuous_demo(15)
#         elif choice == '7':
#             run_continuous_demo(60)
#         elif choice == '8':
#             robot_opmode_start_time_ms = int(time.time() * 1000)
#             print(f"OpMode timer reset/started. Current base time: {robot_opmode_start_time_ms} ms")
#         elif choice == '0':
#             print("Exiting.")
#             break
#         else:
#             print("Invalid choice. Please try again.")
#         time.sleep(0.1) # Small delay
#
# if __name__ == "__main__":
#     try:
#         main_menu()
#     except KeyboardInterrupt:
#         print("\nExiting due to user interrupt.")
#     except Exception as e:
#         print(f"An error occurred in main: {e}")
#         import traceback
#         traceback.print_exc()
#     finally:
#         print("Closing UDP socket.")
#         sock.close()
#
