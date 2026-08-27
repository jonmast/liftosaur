import { Platform } from "react-native";
import NativeLiftosaurWatch, { WatchEvent, WatchAuth } from "../specs/NativeLiftosaurWatch";

export type INativeWatchEvent = WatchEvent;
export type INativeWatchAuth = WatchAuth;

// Both platforms implement the same spec: watchOS over WCSession, Wear OS over the Data Layer.
// The null check is what keeps this honest on a build where the native module didn't register
// (an older OTA bundle against a newer binary, or vice versa) - every guard below routes
// through here rather than re-testing Platform.OS, so there is one place that decides.
const isSupported = (): boolean => (Platform.OS === "ios" || Platform.OS === "android") && NativeLiftosaurWatch != null;

export function NativeWatchBridge_isAvailable(): boolean {
  return isSupported();
}

export function NativeWatchBridge_subscribeToWatchEvents(handler: (event: WatchEvent) => void): () => void {
  if (!isSupported()) {
    return () => {};
  }
  const subscription = NativeLiftosaurWatch!.onWatchEvent(handler);
  NativeLiftosaurWatch!.flushPendingEvents().catch(() => {});
  return () => subscription.remove();
}

export function NativeWatchBridge_sendStorageToWatch(filteredStorageJson: string): void {
  if (!isSupported()) {
    return;
  }
  NativeLiftosaurWatch!.sendStorageToWatch(filteredStorageJson).catch(() => {});
}

export function NativeWatchBridge_sendAuthToWatch(auth: WatchAuth): void {
  if (!isSupported()) {
    return;
  }
  NativeLiftosaurWatch!.sendAuthToWatch(auth).catch(() => {});
}

export function NativeWatchBridge_sendNoAuthToWatch(): void {
  if (!isSupported()) {
    return;
  }
  NativeLiftosaurWatch!.sendNoAuthToWatch().catch(() => {});
}

export function NativeWatchBridge_sendClearAuthToWatch(): void {
  if (!isSupported()) {
    return;
  }
  NativeLiftosaurWatch!.sendClearAuthToWatch().catch(() => {});
}

export function NativeWatchBridge_clearWatchStorage(): void {
  if (!isSupported()) {
    return;
  }
  NativeLiftosaurWatch!.clearWatchStorage().catch(() => {});
}

export function NativeWatchBridge_sendFinishWorkoutToWatch(saveToHealth: boolean): Promise<boolean> {
  if (!isSupported()) {
    return Promise.resolve(false);
  }
  if (Platform.OS === "android") {
    // The boolean means different things on the two platforms, and the caller only understands
    // one of them: it reads `true` as "the watch already wrote this workout to Health", and
    // skips the phone-side write. On watchOS that's real. On Wear OS it is not - the Wear app
    // has no Health Connect integration, so `true` here would silently drop the health record.
    // Kotlin resolves "did the /storage put succeed", which is useful for logs and nothing else,
    // so it is deliberately discarded. Awaiting it still matters: it means the put that ends the
    // workout (activeWorkoutStartTime gone) has been attempted before the app moves on.
    return NativeLiftosaurWatch!
      .sendFinishWorkoutToWatch(saveToHealth)
      .then(() => false)
      .catch(() => false);
  }
  return NativeLiftosaurWatch!.sendFinishWorkoutToWatch(saveToHealth).catch(() => false);
}

export function NativeWatchBridge_sendDiscardWorkoutToWatch(): void {
  if (!isSupported()) {
    return;
  }
  NativeLiftosaurWatch!.sendDiscardWorkoutToWatch().catch(() => {});
}

export function NativeWatchBridge_requestWatchLogs(): Promise<string> {
  if (!isSupported()) {
    return Promise.resolve("");
  }
  return NativeLiftosaurWatch!.requestWatchLogs().catch(() => "");
}

export function NativeWatchBridge_isWatchPaired(): boolean {
  if (!isSupported()) {
    return false;
  }
  return NativeLiftosaurWatch!.isWatchPaired();
}
