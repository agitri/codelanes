from django.dispatch import Signal

# Sent with the new order after it is saved.
order_placed = Signal()
