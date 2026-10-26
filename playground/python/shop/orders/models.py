from dataclasses import dataclass


@dataclass
class Order:
    id: int
    customer: str
    items: int
