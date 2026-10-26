class Signal:
    def __init__(self):
        self.receivers = []

    def connect(self, receiver) -> None:
        self.receivers.append(receiver)

    def send(self, sender, **named) -> list:
        return [(receiver, receiver(sender, **named)) for receiver in self.receivers]


def receiver(signal: Signal, **kwargs):
    def decorate(function):
        signal.connect(function)
        return function
    return decorate
