import UIKit

extension UIApplication {
    /// Close the keyboard, whichever field has it.
    func dismissKeyboard() {
        sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}
