remount_recovery
Ansible role to automatically recover failed mount points by attempting to remount them with escalating steps: This role checks if the specified mount path is properly mounted. If not, it performs a series of recovery actions in this order:

Check if the specified mount path is mounted.

Attempt umount -a and mount -a using handlers.

Restart the vasd service and retry the mount.

Restart the autofs service and retry the mount.

If all above fail, the server will be rebooted to attempt full recovery.

Variable :required_mount_path
Description : The mount path to check and recover |

⚠ Warning If the mount point cannot be recovered through normal means, this role will reboot the server as a last resort. Use with caution in production environments.
