import Foundation
import os

/// The numbers jetsam uses: the process's physical footprint and how much more
/// it may grow before the per-process limit.
enum ProcessMemory {

    /// Bytes left before this process hits its current memory limit.
    static var available: UInt64 {
        UInt64(os_proc_available_memory())
    }

    /// Physical footprint (dirty + compressed + wired), as jetsam counts it.
    static var footprint: UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return kr == KERN_SUCCESS ? UInt64(info.phys_footprint) : 0
    }
}

extension UInt64 {
    var megabytes: Int { Int(self / 1_048_576) }
}
